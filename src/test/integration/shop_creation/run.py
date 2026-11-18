"""Bounded real-Fabric-server regression; invoke with python -X utf8 run.py."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import zipfile


INIT = r"""
import groovy.json.JsonOutput

gradle.beforeProject { p ->
    if (p == p.rootProject) {
        p.layout.buildDirectory.set(new File(System.getenv('SHOP_CREATION_WORK'), 'gradle-build'))
    }
}
gradle.projectsEvaluated {
    rootProject.tasks.register('exportShopCreationLaunch') {
        dependsOn rootProject.tasks.named('configureLaunch'), rootProject.tasks.named('classes')
        doLast {
            def run = rootProject.tasks.named('runServer').get()
            def data = [javaExecutable: run.javaLauncher.get().executablePath.asFile.absolutePath,
                        mainClass: run.mainClass.get(), classpath: run.classpath.asPath,
                        jvmArgs: run.jvmArgs, args: run.args, systemProperties: run.systemProperties]
            new File(System.getenv('SHOP_CREATION_WORK'), 'launch.json').setText(
                JsonOutput.prettyPrint(JsonOutput.toJson(data)), 'UTF-8')
        }
    }
}
"""


def arg_quote(value: object) -> str:
    # Java @argfile quoting, not shell quoting. Double backslashes preserve Windows paths.
    text = str(value)
    return '"' + text.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'


def argfile(path: Path, args: list[str]) -> None:
    path.write_text('\n'.join(arg_quote(arg) for arg in args) + '\n', encoding='utf-8')


def tail(path: Path, chars: int = 7000) -> str:
    if not path.exists():
        return '(log not created)'
    # Bound memory as well as console output, even if startup is noisy.
    with path.open('rb') as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - chars * 4))
        return stream.read().decode('utf-8', errors='replace')[-chars:]


def run_logged(command: list[str], cwd: Path, log: Path, timeout: int, env: dict[str, str]) -> int:
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            # Never taskkill/pkill all Java processes: only this exact process is ours.
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)
            print(tail(log), file=sys.stderr)
            raise RuntimeError(f'Interrupted or timed out after {timeout}s; log: {log}') from None


def launch_jvm_args(export: dict, project: Path) -> list[str]:
    result: list[str] = []
    for raw in export['jvmArgs']:
        arg = str(raw)
        if arg.startswith('@'):
            # Loom currently generates a two-line classpath-only file (the second line can
            # contain unquoted spaces). Flatten it using the exported JavaExec classpath;
            # Java does NOT recursively expand @files embedded inside another @file.
            source = Path(arg[1:].strip('"'))
            if not source.is_absolute():
                source = project / source
            if source.is_file():
                lines = [line.strip() for line in source.read_text(encoding='utf-8').splitlines() if line.strip()]
                if len(lines) != 2 or lines[0] not in ('-classpath', '-cp', '--class-path'):
                    raise RuntimeError(f'Unexpected Loom @file format; refusing to silently discard JVM options: {source}')
            elif source.name != 'runServer' or source.parent.name != 'argFiles':
                raise RuntimeError(f'Missing non-Loom JVM argument file: {source}')
            # A fresh configureLaunch/classes export need not have executed runServer,
            # so its known classpath-only @file may not exist yet. No old run is required.
            continue
        if arg.startswith(('-Xmx', '-Xms', '-XX:ActiveProcessorCount=')):
            continue  # enforce this fixture's resource bound
        result.append(arg)  # including fabric.dli.config/env/main: do not strip named launch data
    result.extend(f'-D{key}={value}' for key, value in export['systemProperties'].items())
    return result


def main() -> int:
    here = Path(__file__).resolve().parent
    project = next((p for p in here.parents if (p / 'gradlew').is_file() and (p / 'build.gradle').is_file()), None)
    if project is None:
        raise RuntimeError('Cannot find project containing gradlew and build.gradle')
    raw_scratch = os.environ.get('PI_SCRATCH_DIR')
    if not raw_scratch or not Path(raw_scratch).is_absolute():
        raise RuntimeError('PI_SCRATCH_DIR must name an absolute scratch directory')
    scratch = Path(raw_scratch).resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='shop-creation-', dir=scratch))
    print(f'Fixture artifacts (retained): {work}', flush=True)
    env = os.environ.copy()
    env['SHOP_CREATION_WORK'] = str(work)
    env['MSYS_NO_PATHCONV'] = '1'
    env['PYTHONUTF8'] = '1'
    # Honor isolation even for native launchers inheriting TMP/TEMP or JAVA_TOOL_OPTIONS.
    for key in ('TMP', 'TEMP', 'TMPDIR'):
        env[key] = str(work)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
        env.pop(key, None)

    init = work / 'export-launch.init.gradle'
    init.write_text(INIT, encoding='utf-8')
    gradle_log = work / 'gradle.log'
    shell_command = f'bash ./gradlew -I {shlex.quote(init.as_posix())} exportShopCreationLaunch --console=plain'
    code = run_logged(['bash', '-c', shell_command], project, gradle_log, 600, env)
    if code:
        print(tail(gradle_log), file=sys.stderr)
        raise RuntimeError(f'Launch export/classes failed ({code}); log: {gradle_log}')
    launch = json.loads((work / 'launch.json').read_text(encoding='utf-8'))
    java = Path(launch['javaExecutable'])
    javac = java.with_name('javac.exe' if java.suffix.lower() == '.exe' else 'javac')
    classes = work / 'probe-classes'
    classes.mkdir()
    compile_args = work / 'compile.args'
    argfile(compile_args, ['-proc:none', '-encoding', 'UTF-8', '-classpath', launch['classpath'],
                           '-d', str(classes), str(here / 'ShopCreationProbe.java')])
    compile_log = work / 'compile.log'
    code = run_logged([str(javac), '@' + str(compile_args)], project, compile_log, 120, env)
    if code:
        print(tail(compile_log), file=sys.stderr)
        raise RuntimeError(f'Probe compilation failed ({code}); log: {compile_log}')

    jar = work / 'shop-creation-probe.jar'
    with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for compiled in classes.rglob('*.class'):
            archive.write(compiled, compiled.relative_to(classes).as_posix())
        archive.writestr('fabric.mod.json', json.dumps({
            'schemaVersion': 1, 'id': 'shop_creation_probe', 'version': '1.0.0',
            'environment': 'server', 'entrypoints': {'main': ['smoke.ShopCreationProbe']},
            'depends': {'habi_train_survive': '*', 'fabricloader': '*', 'fabric-api': '*'},
        }))

    root = work / 'server'
    root.mkdir()
    (root / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    flat = json.dumps({'layers': [{'block': 'minecraft:bedrock', 'height': 1},
                                 {'block': 'minecraft:dirt', 'height': 2},
                                 {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains'})
    (root / 'server.properties').write_text(
        'online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\n'
        'spawn-protection=0\nlevel-type=minecraft:flat\ngenerator-settings=' + flat + '\n'
        'max-tick-time=-1\nenable-query=false\nenable-rcon=false\n', encoding='utf-8')
    jvm = launch_jvm_args(launch, project)
    server_args = [str(a) for a in launch['args']]
    if not any(a in ('nogui', '--nogui') for a in server_args):
        server_args.append('nogui')
    # Each invocation has a unique work directory and each phase additionally deletes its
    # result before starting. An old PASS can never satisfy a failed/aborted fresh run.
    for phase in (1, 2):
        result = work / f'phase{phase}.txt'
        result.unlink(missing_ok=True)
        log = work / f'phase{phase}.log'
        args = jvm + ['-Xms128m', '-Xmx1G', '-XX:ActiveProcessorCount=4', '-Dfile.encoding=UTF-8',
                      '-Djava.io.tmpdir=' + str(work), '-Dfabric.addMods=' + str(jar),
                      '-Dshop.creation.phase=' + str(phase), '-Dshop.creation.result=' + str(result),
                      '-Dshop.creation.state=' + str(work / 'state.properties'),
                      '-classpath', launch['classpath'], launch['mainClass']] + server_args
        java_args = work / f'phase{phase}.args'
        argfile(java_args, args)
        code = run_logged([str(java), '@' + str(java_args)], root, log, 240, env)
        text = result.read_text(encoding='utf-8') if result.exists() else 'NO FRESH RESULT'
        lines = text.splitlines()
        print(f'Phase {phase}: exit={code}; {lines[0] if lines else "EMPTY RESULT"}', flush=True)
        # This fixture currently has fewer than 160 assertions; cap console output anyway.
        for line in lines[1:161]:
            print(line[:500])
        if len(lines) > 161:
            print(f'... full assertions: {result}')
        if code or not text.startswith(f'PASS phase={phase} '):
            print(tail(log), file=sys.stderr)
            raise RuntimeError(f'Phase {phase} failed; result: {result}; log: {log}')
    print('PASS: creation + same-root restart + regular-buyer purchases (quoted totals in assertions).')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, StopIteration) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        raise SystemExit(1)
