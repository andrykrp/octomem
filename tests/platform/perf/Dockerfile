FROM ubuntu:24.04
RUN apt-get update && apt-get install -y libpcre3-dev
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends build-essential autoconf automake libtool pkg-config libevent-dev libssl-dev zlib1g-dev git ca-certificates python3 procps util-linux && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch 2.2.0 https://github.com/RedisLabs/memtier_benchmark.git /opt/memtier && cd /opt/memtier && autoreconf -ivf && ./configure && make -j4
ENV PATH=/opt/memtier:$PATH
WORKDIR /work
