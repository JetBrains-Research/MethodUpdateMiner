#!/usr/bin/env bash

if [ $# -ne "3" ]; then
    echo "usage: ./postprocessing.sh <path to dataset with json list of raw samples> <path to output file> <path to model config>"
    exit 1
fi

# https://stackoverflow.com/a/246128
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
if uname -s | grep -iq cygwin ; then
    DIR=$(cygpath -w "$DIR")
    PWD=$(cygpath -w "$PWD")
fi

"$DIR/gradlew" --stop
"$DIR/gradlew" clean
"$DIR/gradlew" -p "$DIR" runPostProcessing -Pdataset="$PWD/$1" -Poutput="$PWD/$2" -Pconfig="$PWD/$3"