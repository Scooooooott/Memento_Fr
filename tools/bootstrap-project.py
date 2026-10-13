"""Copy explicitly selected source inputs and tool distributions, never caches."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('L:/Words')
ESP = Path('L:/Words_esp')
COPIED = []


def copy_file(source, destination):
    destination = ROOT / destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.read_bytes()!=source.read_bytes():
        raise RuntimeError(f'Refusing to overwrite an existing modified input: {destination}')
    if not destination.exists():
        shutil.copyfile(source, destination)
    COPIED.append({'source': str(source), 'destination': str(destination.relative_to(ROOT)),
                   'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()})


for relative in ('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
                 'gradle/wrapper/gradle-wrapper.properties', 'app/proguard-rules.pro'):
    copy_file(SOURCE / relative, relative)
for source, language, filename in ((SOURCE, 'fr', 'french_content.db'), (ESP, 'es', 'spanish_content.db')):
    copy_file(source / 'app/src/main/assets' / filename, f'data/source-snapshots/{filename}')
    copy_file(source / 'app/src/main/assets' / filename, f'app/src/main/assets/{filename}')
    copy_file(source / 'app/src/main/assets/THIRD_PARTY_CONTENT.md', f'app/src/main/assets/licenses/{language}-content.md')
copy_file(SOURCE / 'app/src/main/assets/licenses/ts-fsrs.txt', 'app/src/main/assets/licenses/ts-fsrs.txt')
copy_file(SOURCE / 'data/raw/open_lexicon/APERTIUM-COPYING', 'app/src/main/assets/licenses/APERTIUM-COPYING')
for relative in ('domain/fsrs/FsrsScheduler.kt', 'domain/fsrs/THIRD_PARTY_LICENSE.txt', 'domain/fsrs/README.md'):
    old = SOURCE / 'app/src/main/java/com/scott/frenchvocab' / relative
    new = ROOT / 'app/src/main/java/com/scott/memento' / relative
    copy_file(old, new.relative_to(ROOT))
    if new.suffix == '.kt':
        new.write_text(new.read_text(encoding='utf-8').replace('com.scott.frenchvocab', 'com.scott.memento'), encoding='utf-8')
copy_file(SOURCE / 'app/src/test/resources/fsrs-5.4.2-reference.csv', 'app/src/test/resources/fsrs-5.4.2-reference.csv')
old_test = SOURCE / 'app/src/test/java/com/scott/frenchvocab/domain/fsrs/FsrsSchedulerTest.kt'
copy_file(old_test, 'app/src/test/java/com/scott/memento/domain/fsrs/FsrsSchedulerTest.kt')
test = ROOT / 'app/src/test/java/com/scott/memento/domain/fsrs/FsrsSchedulerTest.kt'
test.write_text(test.read_text(encoding='utf-8').replace('com.scott.frenchvocab', 'com.scott.memento'), encoding='utf-8')
for folder in ('app/src/main/res/drawable', 'app/src/main/res/mipmap-anydpi'):
    for source in (SOURCE / folder).glob('*.xml'):
        copy_file(source, source.relative_to(SOURCE))

# These are selected SDK tool distributions, not old project/Gradle/user caches.
for relative in ('platforms/android-35', 'build-tools/34.0.0', 'platform-tools', 'licenses', 'emulator'):
    source = SOURCE / '.tools/android-sdk' / relative
    destination = ROOT / '.tools/android-sdk' / relative
    if not destination.exists():
        shutil.copytree(source, destination)
gradle = SOURCE / '.tools/gradle-home/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9'
if not (ROOT / '.tools/gradle-8.9').exists():
    shutil.copytree(gradle, ROOT / '.tools/gradle-8.9')
(ROOT / 'data/manifests').mkdir(parents=True, exist_ok=True)
(ROOT / 'data/manifests/copied-inputs.json').write_text(json.dumps(COPIED, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'copied_source_inputs': len(COPIED), 'sdk_components': 'platform 35, build-tools 34, platform-tools, licenses, emulator',
                  'dependency_cache_copied': False}, ensure_ascii=False))
