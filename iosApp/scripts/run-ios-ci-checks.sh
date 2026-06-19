#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export IOS_AUTOMATED_RELEASE_GATE_SKIP_ARCHIVE=1
export IOS_AUTOMATED_RELEASE_GATE_ONLY_TESTING="${IOS_AUTOMATED_RELEASE_GATE_ONLY_TESTING:-XJTUToolboxIOSTests}"
exec "$script_dir/run-automated-release-gate.sh"
