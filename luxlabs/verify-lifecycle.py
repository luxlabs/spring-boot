#!/usr/bin/env python3
"""Run the HTTP/TLS lifecycle fixture on JDK 11 against the built Maven artifacts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--run-dir', type=Path, required=True)
parser.add_argument('--repo-local', type=Path)
parser.add_argument('--repo-tail', type=Path)
parser.add_argument('--classpath-file', type=Path, help='Use an extracted application classpath instead of the standalone fixture POM')
args = parser.parse_args()
run = args.run_dir.resolve()
run.mkdir(parents=True, exist_ok=False)
java = Path(os.environ['JAVA_HOME']) / 'bin'


def execute(name, command, timeout=180):
    with (run / (name + '.log')).open('w') as log:
        result = subprocess.run([str(c) for c in command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise SystemExit(f'{name} failed ({result.returncode}); see {run / (name + ".log")}')


if args.classpath_file:
    classpath = args.classpath_file.read_text().strip()
else:
    command = ['mvn', '-B', '-ntp', '-f', ROOT / 'luxlabs/compatibility/pom.xml']
    if args.repo_local:
        command.append('-Dmaven.repo.local=' + str(args.repo_local))
    if args.repo_tail:
        command.append('-Dmaven.repo.local.tail=' + str(args.repo_tail))
    command += ['org.apache.maven.plugins:maven-dependency-plugin:3.3.0:build-classpath', '-Dmdep.outputFile=' + str(run / 'classpath.txt')]
    execute('resolve', command)
    classpath = (run / 'classpath.txt').read_text().strip()
(run / 'classes').mkdir()
execute('compile', [java / 'javac', '--release', '11', '-encoding', 'UTF-8', '-cp', classpath, '-d', run / 'classes', ROOT / 'luxlabs/compatibility/BootLifecycleProbe.java'])
execute('keytool', [java / 'keytool', '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-storetype', 'PKCS12', '-keystore', run / 'fixture-only.p12', '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-noprompt'])
command = [java / 'java', '-Djava.io.tmpdir=' + str(run), '-cp', str(run / 'classes') + os.pathsep + classpath, 'BootLifecycleProbe', 'normal', run / 'lifecycle']
started = time.monotonic()
execute('lifecycle', command, timeout=50)
result = json.loads((run / 'lifecycle/result.json').read_text())
(run / 'command.json').write_text(json.dumps({'command': [str(c) for c in command], 'seconds': round(time.monotonic() - started, 3), 'exit_code': 0, 'scope': 'Explicit Boot web auto-configuration, not a complete application bootstrap'}, indent=2) + '\n')
print(json.dumps(result, indent=2))
