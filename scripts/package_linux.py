#!/usr/bin/env python3
"""Package the shaded UrielBotter JAR and the bundled Linux runtimes."""

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import posixpath
from shutil import copyfile, copyfileobj
from stat import S_IFDIR, S_IFLNK, S_IFREG
import tarfile
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


PROJECT = Path(__file__).resolve().parents[1]
TEMURIN_RELEASES = "https://github.com/adoptium/temurin21-binaries/releases/download"
RUNTIMES = {
    "amd64": (
        "jdk-21.0.13.1%2B2",
        "OpenJDK21U-jre_x64_linux_hotspot_21.0.13.1_2.tar.gz",
        "18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0",
    ),
    "arm64": (
        "jdk-21.0.13%2B9",
        "OpenJDK21U-jre_aarch64_linux_hotspot_21.0.13_9.tar.gz",
        "b1734db7ade49c3f6acc6ae06233b8c2dd90e3e2f00f67000ae1c972aaa64d8c",
    ),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_runtime(architecture: str, cache_dir: Path) -> Path:
    release, filename, expected = RUNTIMES[architecture]
    destination = cache_dir / filename
    if destination.is_file() and sha256(destination) == expected:
        return destination

    url = f"{TEMURIN_RELEASES}/{release}/{filename}"
    print(f"Downloading {filename}", flush=True)
    with TemporaryDirectory(dir=cache_dir) as scratch:
        download = Path(scratch) / filename
        with urlopen(url, timeout=60) as response, download.open("wb") as file:
            copyfileobj(response, file)
        if sha256(download) != expected:
            raise ValueError(f"Checksum verification failed for {filename}")
        download.replace(destination)
    return destination


def zip_entry(name: str, mode: int) -> ZipInfo:
    # Explicit Unix permissions preserve executable bits when building on Windows.
    entry = ZipInfo(name)
    entry.create_system = 3
    entry.external_attr = mode << 16
    entry.compress_type = ZIP_DEFLATED
    if name.endswith("/"):
        entry.external_attr |= 0x10
    return entry


def write_runtime(archive: ZipFile, runtime: Path) -> None:
    with tarfile.open(runtime, "r|gz") as tar:
        for member in tar:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"Unexpected runtime path {member.name}")
            name = str(PurePosixPath("runtime", *path.parts[1:]))
            mode = member.mode & 0o777

            if member.isdir():
                archive.writestr(zip_entry(name + "/", S_IFDIR | mode), b"")
            elif member.issym():
                target = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                if not target.startswith("runtime/"):
                    raise ValueError(f"Runtime link escapes the package: {member.name}")
                archive.writestr(zip_entry(name, S_IFLNK | mode), member.linkname.encode("utf-8"))
            elif member.isreg():
                with tar.extractfile(member) as source, archive.open(zip_entry(name, S_IFREG | mode), "w") as target:
                    copyfileobj(source, target)
            else:
                raise ValueError(f"Unsupported runtime entry {member.name}")


def build_archive(output: Path, architecture: str, runtime: Path, jar: Path) -> None:
    launcher = (PROJECT / "launch").read_bytes().replace(b"\r\n", b"\n")
    with ZipFile(output, "w") as archive:
        archive.writestr(zip_entry("launch", S_IFREG | 0o755), launcher)
        archive.writestr(zip_entry("runtime/architecture", S_IFREG | 0o644), architecture + "\n")
        with jar.open("rb") as source, archive.open(zip_entry("urielbotter.jar", S_IFREG | 0o644), "w") as target:
            copyfileobj(source, target)
        readme = PROJECT / "README.md"
        if readme.is_file():
            archive.writestr(zip_entry("README.md", S_IFREG | 0o644), readme.read_bytes())
        write_runtime(archive, runtime)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True, help="destination for ZIP files and SHA256SUMS.txt")
    parser.add_argument("--cache-dir", type=Path, required=True, help="cache directory for Temurin tarballs")
    args = parser.parse_args()

    jar = PROJECT / "target" / "urielbotter.jar"
    if not jar.is_file():
        parser.error(f"Missing JAR at {jar}. Run mvn package first.")
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    with TemporaryDirectory(dir=args.output_dir) as scratch:
        staging = Path(scratch)
        release_jar = staging / "urielbotter.jar"
        copyfile(jar, release_jar)
        sums = [f"{sha256(release_jar)}  {release_jar.name}\n"]
        for architecture in RUNTIMES:
            runtime = download_runtime(architecture, args.cache_dir)
            archive = staging / f"UrielBotter-linux-{architecture}.zip"
            build_archive(archive, architecture, runtime, jar)
            sums.append(f"{sha256(archive)}  {archive.name}\n")
        with (staging / "SHA256SUMS.txt").open("w", encoding="ascii", newline="\n") as file:
            file.writelines(sums)
        for artifact in staging.iterdir():
            os.replace(artifact, args.output_dir / artifact.name)
            print(args.output_dir / artifact.name)


if __name__ == "__main__":
    main()
