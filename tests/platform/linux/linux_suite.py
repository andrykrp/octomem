#!/usr/bin/env python3
"""Linux second-platform acceptance: real-server wire, differential, bounded perf and bounded soak.

Runs on the WSL2 Ubuntu guest as root and drives the Ubuntu 24.04.4 chroot prepared by
setup.sh through raw TCP. Expectations are derived from docs/04 and tests/reports/T013/contract.md.
This file never edits product code, tests, pom.xml or tests/environment.

Usage (from WSL2 Ubuntu):
  python3 tests/platform/linux/linux_suite.py wire
  python3 tests/platform/linux/linux_suite.py perf
  python3 tests/platform/linux/linux_suite.py soak [seconds]
"""
import hashlib
import json
import os
import re
import socket
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path
import importlib.util

# Also works for stand adapters loading this module by absolute file path.
_spec = importlib.util.spec_from_file_location('octomem_oracle_wire', Path(__file__).with_name('oracle_wire.py'))
oracle = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(oracle)

REPO = Path('/mnt/c/java/octomem.octocode.tech')
RS = Path('/root/octomem-linux/rootfs')
OUT = REPO / 'tests/reports/release/testing-rework/wire'
JAR_IN_CHROOT = '/build-cov/target/octomem-1.0.0.jar'
JAR_HOST = RS / 'build-cov/target/octomem-1.0.0.jar'
REF_BIN_IN_CHROOT = '/opt/reference/bin/memcached'
REF_BIN_HOST = RS / 'opt/reference/bin/memcached'
MCFG = REPO / 'compatibility-manifest.json'
REF_TARBALL = REPO / '.tools/archives/memcached-1.6.45.tar.gz'

JVM_FLAGS = ('-Xmx512m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=4 '
             '-XX:+UseSerialGC --enable-native-access=ALL-UNNAMED')
REF_FLAGS = ['-u', 'root', '-l', '127.0.0.1', '-U', '0', '-m', '64', '-t', '4', '-I', '1m', '-c', '1024']
# Linux JDK matching the Windows release toolchain (Eclipse Adoptium Temurin-25.0.4.1+1).
JDK = os.environ.get('OCTOMEM_LINUX_JDK', '/opt/jdk-25.0.4.1+1')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def ensure_mounts():
    subprocess.run(['bash', '-c',
                    'source %s/tests/platform/linux/common.sh; ensure_mounts' % REPO],
                   check=True)


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Server:
    def __init__(self, kind, port, logdir):
        self.kind = kind
        self.port = port
        self.logdir = Path(logdir)
        self.proc = None
        self.log = None

    def command(self):
        if self.kind == 'octomem':
            inner = ('exec %s/bin/java %s -jar %s '
                     '--listen.address=127.0.0.1 --listen.port=%d '
                     '--memory.limit=67108864 --item.maxSize=1048576 '
                     '--transport.workers=4 --log.level=info'
                     % (JDK, ' '.join(JVM_FLAGS.split()), JAR_IN_CHROOT, self.port))
            return ['chroot', str(RS), '/usr/bin/env', '-i',
                    'PATH=%s/bin:/usr/sbin:/usr/bin:/sbin:/bin' % JDK,
                    'HOME=/root', 'JAVA_HOME=%s' % JDK,
                    'LANG=C.UTF-8',
                    'LD_LIBRARY_PATH=%s/lib:%s/lib/server' % (JDK, JDK),
                    '/bin/bash', '-c', inner]
        return ['chroot', str(RS), '/usr/bin/env', '-i',
                'PATH=/usr/sbin:/usr/bin:/sbin:/bin', 'HOME=/root', 'LANG=C.UTF-8',
                REF_BIN_IN_CHROOT] + REF_FLAGS + ['-p', str(self.port)]

    def start(self, timeout=40):
        self.logdir.mkdir(parents=True, exist_ok=True)
        self.log = open(self.logdir / ('%s-%d.log' % (self.kind, self.port)), 'wb')
        self.proc = subprocess.Popen(self.command(), stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError('%s exited early with %s' % (self.kind, self.proc.returncode))
            try:
                with socket.create_connection(('127.0.0.1', self.port), timeout=1):
                    return self
            except OSError:
                time.sleep(0.1)
        raise RuntimeError('%s did not start listening' % self.kind)

    def rss_kb(self):
        try:
            status = Path('/proc/%d/status' % self.proc.pid).read_text()
        except OSError:
            return None
        m = re.search(r'VmRSS:\s+(\d+) kB', status)
        return int(m.group(1)) if m else None

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=10)
        if self.log:
            self.log.close()


class Conn:
    MAX_FRAME = 4 * 1024 * 1024
    MAX_LINE = 8192

    def __init__(self, port, timeout=10):
        if timeout <= 0:
            raise ValueError('positive timeout required')
        self.timeout = timeout
        self.deadline = None
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        self.buf = b''

    def _remaining(self):
        if self.deadline is None:
            self.deadline = time.monotonic() + self.timeout
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('absolute response deadline expired')
        self.sock.settimeout(remaining)

    def send(self, request):
        self.deadline = time.monotonic() + self.timeout
        self._remaining()
        self.sock.sendall(request)

    def _fill(self):
        self._remaining()
        data = self.sock.recv(1 << 16)
        if not data:
            raise EOFError('peer closed before complete response')
        if len(self.buf) + len(data) > self.MAX_FRAME:
            raise ValueError('response buffer limit exceeded')
        self.buf += data
        return data

    def read_raw(self, n):
        if not 0 <= n <= self.MAX_FRAME:
            raise ValueError('invalid payload length')
        while len(self.buf) < n:
            self._fill()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def line(self):
        while b'\r\n' not in self.buf:
            if len(self.buf) > self.MAX_LINE:
                raise ValueError('line limit exceeded')
            self._fill()
        i = self.buf.index(b'\r\n') + 2
        if i > self.MAX_LINE:
            raise ValueError('line limit exceeded')
        out, self.buf = self.buf[:i], self.buf[i:]
        return out

    def read_value(self):
        out = b''
        while True:
            line = self.line()
            out += line
            if len(out) > self.MAX_FRAME:
                raise ValueError('VALUE frame limit exceeded')
            if line == b'END\r\n':
                return out
            parts = line.split()
            if not parts or parts[0] != b'VALUE':
                return out  # unexpected; caller compares
            if not re.fullmatch(rb'VALUE [!-~]+ [0-9]+ [0-9]+(?: [0-9]+)?\r\n', line):
                raise ValueError('malformed VALUE header')
            n = int(parts[3])
            if len(out) + n + 2 > self.MAX_FRAME:
                raise ValueError('VALUE frame limit exceeded')
            payload = self.read_raw(n)
            if self.read_raw(2) != b'\r\n':
                raise ValueError('bad VALUE payload terminator')
            out += payload + b'\r\n'

    def read_until(self, marker=b'END\r\n'):
        out = b''
        while True:
            line = self.line()
            out += line
            if len(out) > self.MAX_FRAME:
                raise ValueError('response frame limit exceeded')
            if line == marker:
                return out

    def read_to_eof(self):
        data, self.buf = self.buf, b''
        while True:
            self._remaining()
            chunk = self.sock.recv(1 << 16)
            if not chunk:
                return data
            data += chunk
            if len(data) > self.MAX_FRAME:
                raise ValueError('EOF frame limit exceeded')

    def expect_silence(self, timeout=0.3):
        if timeout <= 0:
            raise ValueError('positive silence deadline required')
        if self.buf:
            raise ValueError('buffered response to noreply')
        self.sock.settimeout(timeout)
        try:
            extra = self.sock.recv(1)
        except socket.timeout:
            return b''
        if not extra:
            raise EOFError('noreply unexpectedly closed connection')
        raise ValueError('unexpected noreply data: %r' % extra)

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def build_scenario():
    """Deterministic command sequence. Returns list of (name, send_bytes, read_kind)."""
    def s(name, request, kind='line'):
        return {'name': name, 'send': request, 'read': kind}

    steps = [
        s('version', b'version\r\n'),
        s('set_alpha_flags5', b'set alpha 5 0 3\r\nabc\r\n'),
        s('get_alpha', b'get alpha\r\n', 'value'),
        s('gets_alpha', b'gets alpha\r\n', 'value'),
        s('set_beta', b'set beta 0 0 1\r\nB\r\n'),
        s('get_multi_hit_miss', b'get alpha beta missing\r\n', 'value'),
        s('add_existing', b'add beta 0 0 1\r\nZ\r\n'),
        s('add_new_gamma', b'add gamma 0 0 1\r\nG\r\n'),
        s('replace_missing', b'replace missing 0 0 1\r\nx\r\n'),
        s('replace_existing_gamma', b'replace gamma 0 0 1\r\nH\r\n'),
        s('append_alpha', b'append alpha 0 0 2\r\nde\r\n'),
        s('get_after_append', b'get alpha\r\n', 'value'),
        s('prepend_alpha', b'prepend alpha 99 999 1\r\nz\r\n'),
        s('get_after_prepend', b'get alpha\r\n', 'value'),
        s('append_missing', b'append nothere 0 0 1\r\nq\r\n'),
        s('prepend_missing', b'prepend nothere 0 0 1\r\nq\r\n'),
        s('set_num', b'set num 0 0 2\r\n10\r\n'),
        s('incr_num_5', b'incr num 5\r\n'),
        s('decr_num_100_floor', b'decr num 100\r\n'),
        s('incr_wrap', b'set wrap 0 0 1\r\n1\r\n'),
        s('incr_wrap_huge', b'incr wrap 18446744073709551615\r\n'),
        s('incr_nonnumeric', b'incr alpha 1\r\n'),
        s('incr_missing', b'incr nokey 1\r\n'),
        s('decr_missing', b'decr nokey 1\r\n'),
        s('touch_num', b'touch num 0\r\n'),
        s('touch_missing', b'touch nokey 0\r\n'),
        s('gat_alpha', b'gat 0 alpha\r\n', 'value'),
        s('gats_alpha', b'gats 0 alpha\r\n', 'value'),
        s('set_cas_target', b'set cast 0 0 1\r\nA\r\n'),
        s('gets_cas_target', b'gets cast\r\n', 'value'),
        s('cas_mismatch', b'cas cast 0 0 1 1\r\nB\r\n'),
        s('cas_missing', b'cas casmissing 0 0 1 1\r\nB\r\n'),
        s('delete_beta', b'delete beta\r\n'),
        s('delete_again', b'delete beta\r\n'),
        s('get_gamma_before_flush', b'get gamma\r\n', 'value'),
        s('flush_all', b'flush_all\r\n'),
        s('get_gamma_after_flush', b'get gamma\r\n', 'value'),
        s('noreply_set', b'set nr 0 0 1 noreply\r\nN\r\n', 'none'),
        s('noreply_delete', b'delete nr noreply\r\n', 'none'),
        s('noreply_touch', b'touch nr 0 noreply\r\n', 'none'),
        s('noreply_flush', b'flush_all noreply\r\n', 'none'),
        s('sync_after_noreply', b'version\r\n'),
        s('oversize_set', b'set big 0 0 1048577\r\n' + b'x' * 1048577 + b'\r\n'),
        s('unknown_command', b'bogus\r\n'),
        s('empty_line', b'\r\n'),
        s('bad_key_too_long', b'get ' + b'k' * 251 + b'\r\nquit\r\n', 'isolated-close'),
        # Reset after the historical sequence: the following expectations are
        # literal action counts, not derived from product or reference counters.
        s('oracle_cleanup', b'get alpha beta gamma num wrap cast nr\r\n', 'value'),
        s('oracle_reset', b'stats reset\r\n'),
        s('oracle_seed_small', b'set a 0 0 1\r\nx\r\n'),
        s('oracle_seed_large', b'set b 7 0 100\r\n' + b'y' * 100 + b'\r\n'),
        s('oracle_get', b'get a b oracle_missing\r\n', 'value'),
        s('stats', b'stats\r\n', 'stats'),
        s('stats_items', b'stats items\r\n', 'stats'),
        s('stats_slabs', b'stats slabs\r\n', 'stats'),
        s('stats_reset', b'stats reset\r\n'),
        s('quit', b'quit\r\n', 'close'),
    ]
    return steps


def build_isolated_probes():
    """Probes for documented known differences, each on a fresh connection to avoid cascade."""
    return [
        {'name': 'probe_duplicate_key_get', 'setup': [b'set dk 0 0 1\r\nD\r\n'], 'send': b'get dk dk\r\n', 'read': 'value'},
        {'name': 'probe_legacy_delete_time', 'setup': [b'set lk 0 0 1\r\nL\r\n'], 'send': b'delete lk 0\r\n', 'read': 'line'},
        {'name': 'probe_single_lf_terminator', 'setup': [], 'send': b'get lfmissing\n', 'read': 'value'},
    ]


def run_isolated(server):
    out = []
    for probe in build_isolated_probes():
        conn = Conn(server.port)
        try:
            for setup in probe['setup']:
                conn.send(setup)
                if conn.line() != b'STORED\r\n':
                    raise ValueError('isolated probe setup failed')
            conn.send(probe['send'])
            if probe['read'] == 'value':
                recv = conn.read_value()
            else:
                recv = conn.line()
        except (OSError, EOFError, ValueError) as exc:
            recv = ('<%s:%s>' % (type(exc).__name__, exc)).encode()
        finally:
            conn.close()
        out.append({'name': probe['name'], 'send_hex': probe['send'].hex(),
                    'recv_hex': recv.hex()})
    return out


def run_scenario(server, tag):
    transcript = []
    conn = Conn(server.port)
    try:
        for step in build_scenario():
            try:
                if step['read'] == 'isolated-close':
                    probe = Conn(server.port)
                    try:
                        probe.send(step['send'])
                        recv = probe.read_to_eof()
                    finally:
                        probe.close()
                else:
                    conn.send(step['send'])
                    if step['read'] == 'line':
                        recv = conn.line()
                    elif step['read'] == 'value':
                        recv = conn.read_value()
                    elif step['read'] == 'stats':
                        recv = conn.read_until()
                    elif step['read'] == 'close':
                        recv = conn.read_to_eof()
                    else:
                        recv = conn.expect_silence()
            except (OSError, EOFError, ValueError) as exc:
                recv = ('<%s:%s>' % (type(exc).__name__, exc)).encode()
                transcript.append({'name': step['name'], 'send_hex': step['send'].hex(),
                                   'recv_hex': recv.hex(), 'error': str(exc)})
                break
            transcript.append({'name': step['name'], 'send_hex': step['send'].hex(),
                               'recv_hex': recv.hex(), 'send_len': len(step['send']),
                               'recv_len': len(recv)})
    finally:
        conn.close()
    return transcript


CAS_RE = re.compile(rb'^(VALUE \S+ \S+ \S+) (\d+)\r\n', re.M)


def normalize(step_name, data):
    if step_name in ('version', 'sync_after_noreply'):
        return re.sub(rb'VERSION [^\r\n]*\r\n', b'VERSION <NORMALIZED>\r\n', data)
    if step_name in ('gets_alpha', 'gats_alpha', 'gets_cas_target'):
        return CAS_RE.sub(rb'\1 <CAS>\r\n', data)
    return data


def read_expected_checks(transcript):
    """Assert documented octomem behaviour (docs/04). Returns list of check results."""
    raw = {t['name']: bytes.fromhex(t['recv_hex']) for t in transcript}
    checks = []

    def check(name, expected, actual):
        def enc(x):
            return x.hex() if isinstance(x, (bytes, bytearray)) else repr(x)
        checks.append({'name': name, 'ok': expected == actual,
                       'expected': enc(expected), 'actual': enc(actual)})

    names = [t['name'] for t in transcript]
    required = [t['name'] for t in build_scenario()]
    check('complete_unique_scenario', required, names)
    if names != required or any('error' in t for t in transcript):
        if any('error' in t for t in transcript):
            check('scenario_io_success', True, False)
        return checks
    check('oracle_cleanup', b'END\r\n', raw['oracle_cleanup'])
    for name in ('oracle_reset',):
        check(name, b'RESET\r\n', raw[name])
    for name in ('oracle_seed_small', 'oracle_seed_large'):
        check(name, b'STORED\r\n', raw[name])
    check('oracle_seed_payloads', b'VALUE a 0 1\r\nx\r\nVALUE b 7 100\r\n' + b'y' * 100 + b'\r\nEND\r\n', raw['oracle_get'])
    for name in ('noreply_set', 'noreply_delete', 'noreply_touch', 'noreply_flush'):
        check(name, b'', raw[name])

    check('version_octomem_marker', b'VERSION 1.0.0 (octomem)\r\n', raw['version'])
    check('set_stored', b'STORED\r\n', raw['set_alpha_flags5'])
    check('get_flags_and_bytes', b'VALUE alpha 5 3\r\nabc\r\nEND\r\n', raw['get_alpha'])
    check('gets_has_cas', True, bool(re.fullmatch(rb'VALUE alpha 5 3 [0-9]+\r\nabc\r\nEND\r\n', raw['gets_alpha'])))
    check('add_existing_not_stored', b'NOT_STORED\r\n', raw['add_existing'])
    check('add_new_stored', b'STORED\r\n', raw['add_new_gamma'])
    check('replace_missing_not_stored', b'NOT_STORED\r\n', raw['replace_missing'])
    check('replace_existing_stored', b'STORED\r\n', raw['replace_existing_gamma'])
    check('append_combines', b'VALUE alpha 5 5\r\nabcde\r\nEND\r\n', raw['get_after_append'])
    check('append_ignores_flags_exptime', b'VALUE alpha 5 6\r\nzabcde\r\nEND\r\n', raw['get_after_prepend'])
    check('append_missing_not_stored', b'NOT_STORED\r\n', raw['append_missing'])
    check('prepend_missing_not_stored', b'NOT_STORED\r\n', raw['prepend_missing'])
    check('incr_adds', b'15\r\n', raw['incr_num_5'])
    check('decr_floors_at_zero', b'0\r\n', raw['decr_num_100_floor'])
    check('incr_wraps_mod_2_64', b'0\r\n', raw['incr_wrap_huge'])
    check('incr_nonnumeric_error',
          b'CLIENT_ERROR cannot increment or decrement non-numeric value\r\n', raw['incr_nonnumeric'])
    check('incr_missing_not_found', b'NOT_FOUND\r\n', raw['incr_missing'])
    check('decr_missing_not_found', b'NOT_FOUND\r\n', raw['decr_missing'])
    check('touch_touched', b'TOUCHED\r\n', raw['touch_num'])
    check('touch_missing_not_found', b'NOT_FOUND\r\n', raw['touch_missing'])
    check('gat_returns_value', b'VALUE alpha 5 6\r\nzabcde\r\nEND\r\n', raw['gat_alpha'])
    check('gats_returns_cas', True, bool(re.fullmatch(rb'VALUE alpha 5 6 [0-9]+\r\nzabcde\r\nEND\r\n', raw['gats_alpha'])))
    check('cas_mismatch_exists', b'EXISTS\r\n', raw['cas_mismatch'])
    check('cas_missing_not_found', b'NOT_FOUND\r\n', raw['cas_missing'])
    check('delete_first_deleted', b'DELETED\r\n', raw['delete_beta'])
    check('delete_second_not_found', b'NOT_FOUND\r\n', raw['delete_again'])
    check('flush_all_ok', b'OK\r\n', raw['flush_all'])
    check('flush_invalidates', b'END\r\n', raw['get_gamma_after_flush'])
    check('noreply_suppressed', b'VERSION 1.0.0 (octomem)\r\n', raw['sync_after_noreply'])
    check('oversize_error', b'SERVER_ERROR object too large for cache\r\n', raw['oversize_set'])
    check('unknown_error', b'ERROR\r\n', raw['unknown_command'])
    check('empty_line_error', b'ERROR\r\n', raw['empty_line'])
    check('bad_key_client_error', b'CLIENT_ERROR bad command line format\r\n', raw['bad_key_too_long'])
    for name in ('stats', 'stats_items', 'stats_slabs'):
        check(name + '_ends_end', True, raw[name].endswith(b'END\r\n'))
    check('stats_reset_reset', b'RESET\r\n', raw['stats_reset'])
    check('quit_no_response', b'', raw['quit'])

    # mandatory stats inventory (docs/04 184-202 + manifest)
    manifest = json.loads(MCFG.read_text())
    try:
        general = oracle.parse_stats(raw['stats'])
        missing = [f for f in manifest['statsFields'] if f not in general]
        check('stats_mandatory_fields_present', [], missing)
        for field in manifest['statsFields']:
            if field != 'version':
                check('stats_numeric_' + field, True,
                      bool(re.fullmatch(r'[0-9]+', general.get(field, ''))))
        check('stats_action_counters', [], oracle.validate_counters(general, oracle.COUNTERS))
        for name, fields in (('stats_items', 'statsItemsFields'), ('stats_slabs', 'statsSlabsFields')):
            values = oracle.validate_inventory(raw[name], manifest[fields], oracle.EXPECTED_CLASSES)
            expected = ({'items:1:number': 1, 'items:4:number': 1} if name == 'stats_items' else
                        {'1:chunk_size': 64, '4:chunk_size': 136, '1:mem_requested': 40,
                         '4:mem_requested': 136, '1:cmd_set': 1, '4:cmd_set': 1,
                         '1:get_hits': 1, '4:get_hits': 1})
            check(name + '_seed_inventory', [], oracle.validate_counters(values, expected))
    except ValueError as exc:
        check('strict_stats_validation', 'valid mandatory inventory', str(exc))
    return checks


VOLATILE_GENERAL = {'pid', 'uptime', 'time', 'curr_connections', 'total_connections',
                    'connection_structures', 'bytes_read', 'bytes_written', 'conn_yields'}
KNOWN_STATS_EXTENSIONS = {'eviction_policy'}


def stats_fields(text):
    return oracle.parse_stats(text.encode('ascii'))


def differential(octo, ref, octo_iso=None, ref_iso=None):
    steps = []
    required = [t['name'] for t in build_scenario()]
    if [t['name'] for t in octo] != required or [t['name'] for t in ref] != required:
        steps.append({'step': 'complete_unique_scenarios', 'match': False, 'classification': 'UNEXPLAINED'})
    o = {t['name']: t for t in octo}
    r = {t['name']: t for t in ref}
    for name in [t['name'] for t in octo]:
        ot = o[name]
        rt = r.get(name)
        ob = bytes.fromhex(ot['recv_hex'])
        rb = bytes.fromhex(rt['recv_hex']) if rt else b''
        nov, nrv = normalize(name, ob), normalize(name, rb)
        if name in ('stats', 'stats_items', 'stats_slabs'):
            continue
        if name in oracle.PERMITTED_PAIRS:
            steps.append({'step': name, 'match': nov == nrv,
                          'classification': oracle.classify_reply(name, ob, rb),
                           'note': ('Exact pinned response pair; malformed-key probe is isolated through quit/EOF, '
                                    'including the reference double CRLF from proto_text.c:411-426'),
                          'octomem_hex': nov.hex(), 'reference_hex': nrv.hex()})
            continue
        steps.append({'step': name, 'match': nov == nrv,
                      'classification': 'identical' if nov == nrv else 'UNEXPLAINED',
                      'note': '' if nov == nrv else 'semantic difference with no documented reason',
                      'octomem_hex': nov.hex(), 'reference_hex': nrv.hex()})

    if octo_iso is not None and ref_iso is not None:
        probe_names = [p['name'] for p in build_isolated_probes()]
        if [t['name'] for t in octo_iso] != probe_names or [t['name'] for t in ref_iso] != probe_names:
            steps.append({'step': 'complete_unique_isolated', 'match': False, 'classification': 'UNEXPLAINED'})
        oi = {t['name']: t for t in octo_iso}
        ri = {t['name']: t for t in ref_iso}
        notes = {
            'probe_duplicate_key_get': 'docs/04 3.4 + manifest: octomem emits a repeated key once, reference 1.6.45 emits it once per occurrence',
            'probe_legacy_delete_time': 'docs/04 5.1 + manifest: octomem accepts and ignores the legacy delete <time> argument',
            'probe_single_lf_terminator': 'docs/04 2 + manifest: single LF is accepted as a line terminator',
        }
        for name, note in notes.items():
            ob = bytes.fromhex(oi.get(name, {}).get('recv_hex', ''))
            rb = bytes.fromhex(ri.get(name, {}).get('recv_hex', ''))
            steps.append({'step': name, 'match': ob == rb,
                          'classification': oracle.classify_reply(name, ob, rb),
                          'note': note, 'octomem_hex': ob.hex(), 'reference_hex': rb.hex()})

    stats = {}
    for surface, octo_name in (('general', 'stats'), ('items', 'stats_items'), ('slabs', 'stats_slabs')):
        if octo_name not in o or octo_name not in r:
            stats[surface] = {'unexplained_octomem_only': ['missing stats response']}
            continue
        otext = bytes.fromhex(o[octo_name]['recv_hex']).decode('latin1')
        rtext = bytes.fromhex(r[octo_name]['recv_hex']).decode('latin1')
        try:
            of = stats_fields(otext)
            rf = stats_fields(rtext)
        except ValueError as exc:
            stats[surface] = {'unexplained_octomem_only': [str(exc)]}
            continue
        if surface == 'general':
            of = {k: v for k, v in of.items() if k not in ('END',)}
            rf = {k: v for k, v in rf.items() if k not in ('END',)}
        only_o = sorted(set(of) - set(rf))
        only_r = sorted(set(rf) - set(of))

        def known_extension(field):
            if field in KNOWN_STATS_EXTENSIONS:
                return True
            suffix = field.split(':', 1)[1] if ':' in field else field
            return suffix == 'mem_requested'

        differences = []
        if surface == 'general':
            # Pinned 1.6.45 proto_parser.c:706,733 increments once per hit/miss key.
            differences.extend(oracle.validate_counters(of, oracle.COUNTERS))
            ref_expected = dict(oracle.COUNTERS, cmd_get=3)
            differences.extend('reference: ' + e for e in oracle.validate_counters(rf, ref_expected))
            differences.extend(oracle.validate_counters(of, {'bytes': 176, 'curr_items': 2}))
            differences.extend('reference: ' + e for e in oracle.validate_counters(rf, {'bytes': 225, 'curr_items': 2}))
            allowed = VOLATILE_GENERAL | set(oracle.COUNTERS) | {'version', 'bytes', 'curr_items'}
            differences.extend(k for k in of.keys() & rf.keys() if k not in allowed and of[k] != rf[k])
            if of.get('version') != '1.0.0 (octomem)' or rf.get('version') != '1.6.45':
                differences.append('unexpected stats version')
        else:
            manifest = json.loads(MCFG.read_text())
            templates = manifest['statsItemsFields' if surface == 'items' else 'statsSlabsFields']
            try:
                oracle.validate_inventory(otext.encode('ascii'), templates, oracle.EXPECTED_CLASSES)
                oracle.validate_inventory(rtext.encode('ascii'),
                                          [t for t in templates if t != 'N:mem_requested'],
                                          oracle.REFERENCE_CLASSES)
            except ValueError as exc:
                differences.append(str(exc))
            # Layout IDs differ, so compare deterministic per-class counters as totals.
            suffixes = ('number', 'evicted', 'evicted_nonzero', 'outofmemory', 'tailrepairs',
                        'reclaimed', 'expired_unfetched', 'evicted_unfetched', 'crawler_reclaimed') if surface == 'items' else (
                        'get_hits', 'cmd_set', 'delete_hits', 'incr_hits', 'decr_hits', 'cas_hits', 'cas_badval')
            for suffix in suffixes:
                try:
                    ov = [int(v) for k, v in of.items() if k.endswith(':' + suffix)]
                    rv = [int(v) for k, v in rf.items() if k.endswith(':' + suffix)]
                    expected = 2 if suffix in ('number', 'get_hits', 'cmd_set') else 0
                    if not ov or not rv or sum(ov) != expected or sum(rv) != expected:
                        differences.append('exact class total ' + suffix)
                except ValueError:
                    differences.append('malformed class counter ' + suffix)
            # Only these class fields are layout/age-dependent. New unknown
            # fields on the product side need explicit review, not a name mask.
            allowed_suffixes = set(suffixes) | ({'age', 'evicted_time'} if surface == 'items' else {
                'chunk_size', 'chunks_per_page', 'total_pages', 'total_chunks',
                'used_chunks', 'free_chunks', 'free_chunks_end', 'mem_requested'})
            differences.extend('unclassified class field ' + key for key in of
                               if ':' in key and key.rsplit(':', 1)[1] not in allowed_suffixes)
        stats[surface] = {
            'classes_octomem': sorted({int(m) for m in re.findall(r'(\d+):', otext)}),
            'classes_reference': sorted({int(m) for m in re.findall(r'(\d+):', rtext)}),
            'only_octomem': only_o,
            'only_reference': only_r,
            'known_octomem_only': [f for f in only_o if known_extension(f)],
            'unexplained_octomem_only': ([f for f in only_o if not known_extension(f)] if surface == 'general' else []) + differences,
            'value_differences_classified': 'Exact action counters checked; class geometry/record bytes differ by allocator; ages and process/connection clocks are volatile',
        }
    return {'steps': steps, 'stats': stats}


def write_json(path, obj):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(obj, indent=2, ensure_ascii=False))


def cmd_wire():
    global OUT
    OUT = OUT / ('run-%d' % time.time_ns())
    ensure_mounts()
    OUT.mkdir(parents=True, exist_ok=True)
    if sha256_file(JAR_HOST).lower() != '63764015708edbdcb2284df53e827fa5cf2d58e904e73d3004d3f7d0165f9b70':
        raise ValueError('candidate JAR differs from pinned wire identity')
    meta = {
        'harness_sha256': sha256_file(Path(__file__)),
        'oracle_sha256': sha256_file(Path(oracle.__file__)),
        'manifest_sha256': sha256_file(MCFG),
        'expected_octomem_classes': sorted(oracle.EXPECTED_CLASSES),
        'expected_reference_classes': sorted(oracle.REFERENCE_CLASSES),
        'octomem_jar': JAR_IN_CHROOT,
        'octomem_jar_sha256': sha256_file(JAR_HOST),
        'reference_binary': REF_BIN_IN_CHROOT,
        'reference_binary_sha256': sha256_file(REF_BIN_HOST),
        'reference_source_tarball': str(REF_TARBALL),
        'reference_source_sha256': sha256_file(REF_TARBALL),
        'reference_version': '1.6.45',
        'octomem_jvm_flags': JVM_FLAGS,
        'reference_flags': REF_FLAGS,
    }
    results = {'meta': meta}
    for tag, kind in (('octomem', 'octomem'), ('reference', 'reference')):
        port = free_port()
        srv = Server(kind, port, OUT / 'wire')
        try:
            srv.start()
            transcript = run_scenario(srv, tag)
            write_json(OUT / 'wire' / ('transcript-%s.json' % tag), transcript)
            isolated = run_isolated(srv)
            write_json(OUT / 'wire' / ('isolated-%s.json' % tag), isolated)
        finally:
            srv.stop()
        results[tag] = transcript
        results[tag + '_isolated'] = isolated
    results['checks'] = read_expected_checks(results['octomem'])
    results['differential'] = differential(results['octomem'], results['reference'],
                                           results['octomem_isolated'], results['reference_isolated'])
    failed = [c for c in results['checks'] if not c['ok']]
    unexplained = [s for s in results['differential']['steps'] if s['classification'] == 'UNEXPLAINED']
    stats_unexplained = {k: v['unexplained_octomem_only'] for k, v in results['differential']['stats'].items()
                         if v['unexplained_octomem_only']}
    results['summary'] = {
        'checks_total': len(results['checks']), 'checks_failed': len(failed),
        'failed_checks': [c['name'] for c in failed],
        'unexplained_step_differences': [s['step'] for s in unexplained],
        'unexplained_stats_fields': stats_unexplained,
        'known_step_differences': [s['step'] for s in results['differential']['steps']
                                   if s['classification'] == 'known-difference'],
    }
    write_json(OUT / 'wire' / 'wire-acceptance.json', results)
    print(json.dumps(results['summary'], indent=2))
    return 0 if not failed and not unexplained and not stats_unexplained else 1


# ----------------------------- bounded performance -----------------------------

def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    idx = min(len(values) - 1, int(round((p / 100.0) * (len(values) - 1))))
    return values[idx]


def perf_client(port, ops, threads, keyspace, duration=None):
    if min(ops, threads, keyspace) <= 0 or (duration is not None and duration <= 0):
        raise ValueError('positive workload bounds required')
    latencies = []
    lock = threading.Lock()
    errors = [0]
    stop = threading.Event()
    connections = set()
    completed = set()
    deadline = time.monotonic() + (duration if duration is not None else max(30, ops * 0.02)) + 5

    def worker(seed):
        import random
        rnd = random.Random(seed)
        c = None
        local = []
        n = 0
        start = time.perf_counter()
        expected = {}
        try:
            c = Conn(port, timeout=2)
            with lock:
                connections.add(c)
            while n < ops and not stop.is_set():
                if duration is not None and time.perf_counter() - start >= duration:
                    break
                # Worker-owned key partitions make wrong/missing payloads observable,
                # with no race against another client's legitimate replacement.
                key = b'key%d_%06d' % (seed, rnd.randrange(keyspace))
                t0 = time.perf_counter()
                if rnd.random() < 0.2:
                    size = rnd.choice([16, 64, 256, 1024])
                    val = (b'v' * size)
                    c.send(b'set ' + key + b' 0 0 ' + str(len(val)).encode() + b'\r\n' + val + b'\r\n')
                    resp = c.line()
                    if resp != b'STORED\r\n':
                        raise ValueError('SET failed: %r' % resp)
                    expected[key] = val
                else:
                    # Establish a known state even if a prior invocation used this key.
                    if key not in expected:
                        c.send(b'delete ' + key + b'\r\n')
                        if c.line() not in (b'DELETED\r\n', b'NOT_FOUND\r\n'):
                            raise ValueError('GET setup delete failed')
                        expected[key] = None
                    c.send(b'get ' + key + b'\r\n')
                    val = expected[key]
                    wanted = b'END\r\n' if val is None else (b'VALUE ' + key + b' 0 ' + str(len(val)).encode() + b'\r\n' + val + b'\r\nEND\r\n')
                    if c.read_value() != wanted:
                        raise ValueError('GET payload mismatch')
                local.append(time.perf_counter() - t0)
                n += 1
            if not stop.is_set():
                with lock:
                    completed.add(seed)
        except Exception:
            with lock:
                errors[0] += 1
            stop.set()
        finally:
            if c is not None:
                c.close()
            with lock:
                connections.discard(c)
                latencies.extend(local)

    ths = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(threads)]
    t0 = time.perf_counter()
    for t in ths:
        t.start()
    try:
        for t in ths:
            t.join(timeout=max(0, deadline - time.monotonic()))
    finally:
        stop.set()
        with lock:
            active = list(connections)
        for c in active:
            c.close()
        cleanup_deadline = time.monotonic() + 3
        for t in ths:
            t.join(timeout=max(0, cleanup_deadline - time.monotonic()))
    alive_workers = sum(t.is_alive() for t in ths)
    errors[0] += threads - len(completed) + alive_workers
    elapsed = time.perf_counter() - t0
    total = len(latencies)
    return {
        'ops': total,
        'elapsed_s': elapsed,
        'throughput_ops_s': total / elapsed if elapsed else None,
        'latency_ms_p50': (percentile(latencies, 50) or 0) * 1000,
        'latency_ms_p95': (percentile(latencies, 95) or 0) * 1000,
        'latency_ms_p99': (percentile(latencies, 99) or 0) * 1000,
        'latency_ms_mean': (statistics.fmean(latencies) * 1000) if latencies else None,
        'latency_ms_max': (max(latencies) * 1000) if latencies else None,
        'errors': errors[0],
        'workers_completed': len(completed),
        'workers_alive': alive_workers,
        'expected_ops': ops * threads if duration is None else None,
        'valid': not errors[0] and not alive_workers and (duration is not None or total == ops * threads),
    }


def cmd_perf():
    global OUT
    OUT = OUT / ('run-%d' % time.time_ns())
    ensure_mounts()
    OUT.mkdir(parents=True, exist_ok=True)
    hardware = {
        'nproc': os.cpu_count(),
        'cpu_model': next((l.split(':', 1)[1].strip() for l in Path('/proc/cpuinfo').read_text().splitlines()
                           if l.startswith('model name')), 'unknown'),
        'mem_total': next((l.split(':', 1)[1].strip() for l in Path('/proc/meminfo').read_text().splitlines()
                           if l.startswith('MemTotal')), 'unknown'),
    }
    report = {
        'label': 'BOUNDED/LOCAL measurement, NOT a release performance claim',
        'octomem_jar_sha256': sha256_file(JAR_HOST),
        'reference_binary_sha256': sha256_file(REF_BIN_HOST),
        'octomem_jvm_flags': JVM_FLAGS,
        'reference_flags': REF_FLAGS,
        'client': {'threads': 8, 'keyspace': 2000, 'payload_mix': '80% get / 20% set, value sizes 16/64/256/1024 bytes',
                   'keyspace_scope': 'per worker, disjoint worker-owned keys',
                   'get_setup': 'first GET of each key performs validated DELETE; timed and not counted as a workload op',
                   'warmup_ops_per_thread': 20000, 'measured_ops_per_thread': 100000, 'client_language': 'Python 3 threads'},
        'hardware': hardware,
        'servers': {},
    }
    for kind in ('octomem', 'reference'):
        port = free_port()
        srv = Server(kind, port, OUT / 'perf')
        try:
            srv.start()
            warm = perf_client(port, 20000, 8, 2000)
            # settle
            time.sleep(2)
            rss_before = srv.rss_kb()
            measured = perf_client(port, 100000, 8, 2000)
            rss_after = srv.rss_kb()
        finally:
            srv.stop()
        report['servers'][kind] = {'warmup': warm, 'measured': measured,
                                   'rss_kb_before': rss_before, 'rss_kb_after': rss_after,
                                   '_note': 'RSS is the chroot java/memcached process resident set on the WSL2 guest'}
    write_json(OUT / 'perf' / 'perf-bounded.json', report)
    print(json.dumps(report['servers'], indent=2))
    return 0 if all(s[phase]['valid'] for s in report['servers'].values()
                    for phase in ('warmup', 'measured')) else 1


# ----------------------------- bounded soak -----------------------------

def cmd_soak(seconds=1800):
    global OUT
    OUT = OUT / ('run-%d' % time.time_ns())
    ensure_mounts()
    OUT.mkdir(parents=True, exist_ok=True)
    import random
    port = free_port()
    srv = Server('octomem', port, OUT / 'soak')
    samples = []
    errors = [0]
    ops = [0]
    stop = threading.Event()
    lock = threading.Lock()
    connections = set()
    completed = set()

    def worker(seed):
        rnd = random.Random(seed)
        c = None
        expected = {}
        try:
            c = Conn(port, timeout=2)
            with lock:
                connections.add(c)
            while not stop.is_set():
                key = b'soak%d_%05d' % (seed, rnd.randrange(5000))
                if rnd.random() < 0.3:
                    val = b'v' * rnd.choice([8, 32, 128, 512])
                    c.send(b'set ' + key + b' 0 0 ' + str(len(val)).encode() + b'\r\n' + val + b'\r\n')
                    resp = c.line()
                    if resp != b'STORED\r\n':
                        raise ValueError('soak SET failed')
                    expected[key] = val
                elif rnd.random() < 0.5:
                    c.send(b'gets ' + key + b'\r\n')
                    actual = c.read_value()
                    val = expected.get(key)
                    wanted = b'END\r\n' if val is None else (b'VALUE ' + key + b' 0 ' + str(len(val)).encode() + b' <CAS>\r\n' + val + b'\r\nEND\r\n')
                    if CAS_RE.sub(rb'\1 <CAS>\r\n', actual) != wanted:
                        raise ValueError('soak GET payload mismatch')
                elif rnd.random() < 0.7:
                    c.send(b'delete ' + key + b'\r\n')
                    wanted = b'DELETED\r\n' if key in expected else b'NOT_FOUND\r\n'
                    if c.line() != wanted:
                        raise ValueError('soak DELETE mismatch')
                    expected.pop(key, None)
                elif rnd.random() < 0.85:
                    c.send(b'touch ' + key + b' 0\r\n')
                    wanted = b'TOUCHED\r\n' if key in expected else b'NOT_FOUND\r\n'
                    if c.line() != wanted:
                        raise ValueError('soak TOUCH mismatch')
                else:
                    c.send(b'stats\r\n')
                    if 'cmd_set' not in oracle.parse_stats(c.read_until()):
                        raise ValueError('soak STATS missing cmd_set')
                with lock:
                    ops[0] += 1
            with lock:
                completed.add(seed)
        except Exception:
            with lock:
                errors[0] += 1
            stop.set()
        finally:
            if c is not None:
                c.close()
            with lock:
                connections.discard(c)

    if seconds <= 0:
        raise ValueError('positive soak duration required')
    ths = []
    try:
        srv.start()
        ths = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(6)]
        for t in ths:
            t.start()
        start = time.monotonic()
        while time.monotonic() - start < seconds and not stop.is_set():
            stop.wait(min(15, max(0, seconds - (time.monotonic() - start))))
            samples.append({'t_s': round(time.monotonic() - start, 1), 'rss_kb': srv.rss_kb(),
                             'ops': ops[0], 'errors': errors[0]})
        stop.set()
        deadline = time.monotonic() + 3
        for t in ths:
            t.join(timeout=max(0, deadline - time.monotonic()))
        alive = srv.proc.poll() is None
    finally:
        stop.set()
        with lock:
            active = list(connections)
        for c in active:
            c.close()
        deadline = time.monotonic() + 3
        for t in ths:
            t.join(timeout=max(0, deadline - time.monotonic()))
        srv.stop()
    errors[0] += 6 - len(completed) + sum(t.is_alive() for t in ths)
    report = {
        'label': ('BOUNDED local soak. This does NOT satisfy the mandatory 72-hour '
                  'endurance requirement; it is a bounded smoke soak only.'),
        'duration_s_target': seconds,
        'duration_s_actual': samples[-1]['t_s'] if samples else 0,
        'client_threads': 6,
        'mixed_workload': 'set/gets/delete/touch/stats',
        'octomem_jar_sha256': sha256_file(JAR_HOST),
        'octomem_jvm_flags': JVM_FLAGS,
        'server_alive_at_end': alive,
        'total_ops': ops[0],
        'total_errors': errors[0],
        'workers_completed': len(completed),
        'workers_alive': sum(t.is_alive() for t in ths),
        'rss_kb_first': samples[0]['rss_kb'] if samples else None,
        'rss_kb_last': samples[-1]['rss_kb'] if samples else None,
        'rss_kb_max': max((s['rss_kb'] or 0) for s in samples) if samples else None,
        'rss_samples': samples,
        'endurance_72h_satisfied': False,
    }
    write_json(OUT / 'soak' / 'soak-bounded.json', report)
    print(json.dumps({k: v for k, v in report.items() if k != 'rss_samples'}, indent=2))
    return 0 if alive and not errors[0] and ops[0] > 0 else 1


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'wire'
    if mode == 'wire':
        sys.exit(cmd_wire())
    if mode == 'perf':
        sys.exit(cmd_perf())
    if mode == 'soak':
        sys.exit(cmd_soak(int(sys.argv[2]) if len(sys.argv) > 2 else 1800))
    raise SystemExit('unknown mode %s' % mode)
