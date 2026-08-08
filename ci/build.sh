#!/bin/sh
# What CI does.
#
# The workflow that calls this holds only what CI *is* — triggers, runner, JDK,
# action versions — because changing that file needs the owner. Everything about
# what actually runs lives here, and here is writable by whoever is building
# this repository. Adding a check is an edit to this file that reaches the next
# push. See ci/README.md for why the split exists.
#
# Run it locally the same way CI does: sh ci/build.sh

set -eu

# -B is batch mode: no ANSI, no interactive prompts, and a log a human can read
# after the fact rather than a progress spinner.
mvn -B clean package

# Tier 2: boot the jar that was just shaded on a real Paper server and assert
# from inside it. The build above cannot tell whether the artifact starts - a
# green build, green CI and a green tier-1 suite have all been observed over a
# jar that died in onEnable. See ci/integration.sh.
sh ci/integration.sh
