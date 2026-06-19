#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export IOS_AUTOMATED_RELEASE_GATE_SKIP_ARCHIVE=1
exec "$script_dir/run-automated-release-gate.sh"
