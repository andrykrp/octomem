"""Pure, fail-closed wire oracles; expectations never come from observed replies."""
import re


EXPECTED_CLASSES = frozenset({1, 4})
# Reference 1.6.45 x86-64 default sizes 96/120/152/192; records 61/164
# (48-byte item, CAS 8, key+NUL 2, data+CRLF 3/102, flags 0/4).
# Octomem sizes 64/80/104/136; aligned records 40/136. IDs happen to agree.
REFERENCE_CLASSES = frozenset({1, 4})
COUNTERS = dict.fromkeys(('cmd_get cmd_set cmd_flush cmd_touch get_hits get_misses '
                         'delete_hits delete_misses incr_hits incr_misses decr_hits '
                         'decr_misses cas_hits cas_misses cas_badval touch_hits '
                         'touch_misses total_items reclaimed expired_unfetched '
                         'evicted_unfetched evictions').split(), 0)
COUNTERS.update(cmd_get=3, cmd_set=2, get_hits=2, get_misses=1, total_items=2)
PERMITTED_PAIRS = {
    # Pinned reference error is CRLF-terminated twice (proto_text.c:411-426).
    # The isolated request ends with quit; EOF bounds the complete response.
    'bad_key_too_long': (b'CLIENT_ERROR bad command line format\r\n',
                         b'CLIENT_ERROR bad command line format\r\n\r\n'),
    'empty_line': (b'ERROR\r\n', b'CLIENT_ERROR bad command line format\r\n'),
    'probe_duplicate_key_get': (b'VALUE dk 0 1\r\nD\r\nEND\r\n',
                                b'VALUE dk 0 1\r\nD\r\nVALUE dk 0 1\r\nD\r\nEND\r\n'),
    'probe_legacy_delete_time': (b'DELETED\r\n', b'DELETED\r\n'),
    'probe_single_lf_terminator': (b'END\r\n', b'END\r\n'),
    'version': (b'VERSION 1.0.0 (octomem)\r\n', b'VERSION 1.6.45\r\n'),
    'sync_after_noreply': (b'VERSION 1.0.0 (octomem)\r\n', b'VERSION 1.6.45\r\n'),
}


def parse_stats(data):
    """Return unique ASCII STAT keys/values; reject incomplete/extra/malformed frames."""
    if not isinstance(data, bytes) or len(data) > 4 * 1024 * 1024:
        raise ValueError('invalid stats frame size/type')
    lines = data.split(b'\r\n')
    if lines[-2:] != [b'END', b'']:
        raise ValueError('stats requires terminal END CRLF')
    fields = {}
    for line in lines[:-2]:
        match = re.fullmatch(rb'STAT ([!-~]+) ([ -~]+)', line)
        if not match:
            raise ValueError('malformed STAT line')
        key, value = (part.decode('ascii') for part in match.groups())
        if key in fields:
            raise ValueError('duplicate STAT ' + key)
        fields[key] = value
    return fields


def validate_inventory(data, templates, expected_classes):
    """Require every template for expected AND observed classes, returning parsed fields.

    Templates are exactly items:N:suffix or N:suffix. Expected IDs must be
    independently supplied and nonempty; additional observed classes are checked too.
    """
    expected = set(expected_classes)
    if not expected or any(type(c) is not int or c <= 0 for c in expected):
        raise ValueError('nonempty positive expected class IDs required')
    templates = tuple(templates)
    if not templates or len(set(templates)) != len(templates):
        raise ValueError('empty/duplicate inventory templates')
    items = templates[0].startswith('items:N:')
    prefix = 'items:N:' if items else 'N:'
    if any(not t.startswith(prefix) or not re.fullmatch(r'[a-z_]+', t[len(prefix):])
           for t in templates):
        raise ValueError('invalid inventory template')
    fields = parse_stats(data)
    pattern = r'items:([1-9][0-9]*):([a-z_]+)' if items else r'([1-9][0-9]*):([a-z_]+)'
    observed = set()
    for key, value in fields.items():
        match = re.fullmatch(pattern, key)
        if match:
            observed.add(int(match[1]))
            if not re.fullmatch(r'[0-9]+', value):
                raise ValueError('nonnumeric class field ' + key)
        elif ':' in key or items:
            raise ValueError('malformed class field ' + key)
    missing = [t.replace('N:', str(c) + ':', 1)
               for c in sorted(expected | observed) for t in templates
               if t.replace('N:', str(c) + ':', 1) not in fields]
    if missing:
        raise ValueError('missing inventory fields: ' + ','.join(missing))
    for c in expected:
        key = ('items:%d:number' if items else '%d:used_chunks') % c
        if int(fields[key]) <= 0:
            raise ValueError('seeded class is not populated: ' + key)
    return fields


def validate_counters(fields, expected):
    """Return missing, malformed or unequal exact-counter diagnostics (empty means valid)."""
    return ['%s expected %s got %r' % (key, value, fields.get(key))
            for key, value in expected.items()
            if fields.get(key) != str(value)]


def classify_reply(name, octo, ref):
    """Known names authorize only their complete literal response pair, never arbitrary bytes."""
    if name in PERMITTED_PAIRS:
        if (octo, ref) != PERMITTED_PAIRS[name]:
            return 'UNEXPLAINED'
        return 'identical' if octo == ref else 'known-difference'
    return 'identical' if octo == ref else 'UNEXPLAINED'
