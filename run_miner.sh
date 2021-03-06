#!/usr/bin/env bash

if [ $# -ne "3" ]; then
  echo "usage: ./run_miner.sh <path to project list file> <path to output folder> <path to statistic output>"
  exit 1
fi

# https://stackoverflow.com/a/246128
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
if uname -s | grep -iq cygwin; then
  DIR=$(cygpath -w "$DIR")
  PWD=$(cygpath -w "$PWD")
fi

"$DIR/gradlew" --stop
"$DIR/gradlew" -p "$DIR" clean
echo "DIR $DIR"
echo "PWD $PWD"
"$DIR/gradlew" -p "$DIR" runMiner -Prunner="Miner" -Pdataset="$PWD/$1" -Poutput="$PWD/$2" -PstatsOutput="$PWD/$3" --stacktrace
