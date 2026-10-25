"""Historical diagnostic entrypoint, intentionally not an acceptance-data producer.

The previous unpaired fixed-order P08 scaling experiment cannot explain P03.
A new diagnostic needs its own approved workload-preserving intervention plan,
explicit runtime identity, unique attempt and uninstrumented comparison.
"""
import argparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    parser.error("diagnostic execution NOT_RUN: obtain a separate paired intervention plan; historical artifacts remain immutable")


if __name__ == "__main__":
    main()
