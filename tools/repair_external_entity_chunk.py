#!/usr/bin/env python3
"""Stream-filter plugin-owned entities from an external Minecraft .mcc NBT file."""

from __future__ import annotations

import argparse
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
from typing import BinaryIO
import zlib


DEFAULT_MARKER = b"rookieauctions:immersive-venue-display"
CHUNK_SIZE = 1024 * 1024
FIXED_PAYLOAD_SIZES = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}


class RepairError(RuntimeError):
	pass


class ZlibReader:
	def __init__(self, source: BinaryIO):
		self.source = source
		self.decompressor = zlib.decompressobj()
		self.buffer = bytearray()
		self.source_eof = False
		self.uncompressed_bytes = 0

	def read(self, size: int) -> bytes:
		if size < 0:
			raise RepairError("negative read size")
		while len(self.buffer) < size and not self.source_eof:
			compressed = self.source.read(CHUNK_SIZE)
			if compressed:
				self.buffer.extend(self.decompressor.decompress(compressed))
				if self.decompressor.unused_data:
					raise RepairError("trailing data after zlib stream")
			else:
				self.buffer.extend(self.decompressor.flush())
				self.source_eof = True
				if not self.decompressor.eof:
					raise RepairError("truncated zlib stream")
		result = bytes(self.buffer[:size])
		del self.buffer[:size]
		self.uncompressed_bytes += len(result)
		return result

	def exact(self, size: int) -> bytes:
		result = self.read(size)
		if len(result) != size:
			raise RepairError(
				f"truncated NBT at uncompressed byte {self.uncompressed_bytes}: "
				f"wanted {size}, got {len(result)}"
			)
		return result

	def finish(self) -> None:
		if self.read(1):
			raise RepairError("trailing bytes after root NBT tag")


def i32(raw: bytes) -> int:
	return struct.unpack(">i", raw)[0]


def checked_count(raw: bytes, label: str) -> int:
	value = i32(raw)
	if value < 0:
		raise RepairError(f"negative {label}: {value}")
	return value


def copy_exact(reader: ZlibReader, output: BinaryIO, size: int) -> None:
	remaining = size
	while remaining:
		piece = reader.exact(min(remaining, CHUNK_SIZE))
		output.write(piece)
		remaining -= len(piece)


def copy_string(reader: ZlibReader, output: BinaryIO) -> bytes:
	raw_size = reader.exact(2)
	output.write(raw_size)
	size = struct.unpack(">H", raw_size)[0]
	value = reader.exact(size)
	output.write(value)
	return value


def copy_payload(reader: ZlibReader, output: BinaryIO, tag_type: int) -> None:
	if tag_type in FIXED_PAYLOAD_SIZES:
		copy_exact(reader, output, FIXED_PAYLOAD_SIZES[tag_type])
		return
	if tag_type == 7:
		raw_count = reader.exact(4)
		output.write(raw_count)
		copy_exact(reader, output, checked_count(raw_count, "byte-array length"))
		return
	if tag_type == 8:
		copy_string(reader, output)
		return
	if tag_type == 9:
		element_type = reader.exact(1)
		raw_count = reader.exact(4)
		output.write(element_type)
		output.write(raw_count)
		count = checked_count(raw_count, "list length")
		for _ in range(count):
			copy_payload(reader, output, element_type[0])
		return
	if tag_type == 10:
		copy_compound(reader, output, root=False, marker=None, result=None)
		return
	if tag_type == 11:
		raw_count = reader.exact(4)
		output.write(raw_count)
		copy_exact(reader, output, checked_count(raw_count, "int-array length") * 4)
		return
	if tag_type == 12:
		raw_count = reader.exact(4)
		output.write(raw_count)
		copy_exact(reader, output, checked_count(raw_count, "long-array length") * 8)
		return
	raise RepairError(f"unsupported NBT tag type {tag_type}")


def filter_entities_list(
	reader: ZlibReader,
	output: BinaryIO,
	marker: bytes,
	result: dict[str, int],
) -> None:
	element_type = reader.exact(1)
	if element_type != b"\x0a":
		raise RepairError(f"Entities list contains tag type {element_type[0]}, expected compound")
	output.write(element_type)
	raw_count = reader.exact(4)
	original_count = checked_count(raw_count, "Entities list length")
	count_position = output.tell()
	output.write(b"\0\0\0\0")
	kept = 0
	removed = 0
	for _ in range(original_count):
		entity_output = BytesIO()
		copy_payload(reader, entity_output, 10)
		entity = entity_output.getvalue()
		if marker in entity:
			removed += 1
		else:
			output.write(entity)
			kept += 1
	end_position = output.tell()
	output.seek(count_position)
	output.write(struct.pack(">i", kept))
	output.seek(end_position)
	result.update(original_entities=original_count, removed_entities=removed, kept_entities=kept)


def copy_compound(
	reader: ZlibReader,
	output: BinaryIO,
	*,
	root: bool,
	marker: bytes | None,
	result: dict[str, int] | None,
) -> None:
	while True:
		tag_type = reader.exact(1)[0]
		output.write(bytes((tag_type,)))
		if tag_type == 0:
			return
		name = copy_string(reader, output)
		if root and tag_type == 9 and name == b"Entities":
			if marker is None or result is None:
				raise RepairError("missing entity filter state")
			if "original_entities" in result:
				raise RepairError("duplicate root Entities list")
			filter_entities_list(reader, output, marker, result)
		else:
			copy_payload(reader, output, tag_type)


def sha256_file(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as source:
		for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
			digest.update(chunk)
	return digest.hexdigest()


def compress_raw(raw_path: Path, output_path: Path) -> tuple[int, int, str]:
	compressor = zlib.compressobj(level=9)
	raw_digest = hashlib.sha256()
	raw_bytes = 0
	with raw_path.open("rb") as source, output_path.open("xb") as output:
		for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
			raw_digest.update(chunk)
			raw_bytes += len(chunk)
			output.write(compressor.compress(chunk))
		output.write(compressor.flush())
		output.flush()
		os.fsync(output.fileno())
	return raw_bytes, output_path.stat().st_size, raw_digest.hexdigest()


def verify_compressed(
	path: Path,
	marker: bytes,
	expected_raw_bytes: int,
	expected_raw_sha256: str,
) -> None:
	decompressor = zlib.decompressobj()
	digest = hashlib.sha256()
	total = 0
	overlap = b""
	marker_found = False
	with path.open("rb") as source:
		for compressed in iter(lambda: source.read(CHUNK_SIZE), b""):
			plain = decompressor.decompress(compressed)
			digest.update(plain)
			total += len(plain)
			window = overlap + plain
			if marker in window:
				marker_found = True
			overlap = window[-max(0, len(marker) - 1):]
		plain = decompressor.flush()
		digest.update(plain)
		total += len(plain)
		if marker in overlap + plain:
			marker_found = True
	if not decompressor.eof:
		raise RepairError("repaired output has a truncated zlib stream")
	if marker_found:
		raise RepairError("repaired output still contains the target marker")
	if total != expected_raw_bytes or digest.hexdigest() != expected_raw_sha256:
		raise RepairError("compressed output failed raw round-trip verification")


def repair(
	source_path: Path,
	output_path: Path,
	marker: bytes,
	expected_remove: int | None,
	expected_keep: int | None,
) -> dict[str, object]:
	if source_path.resolve() == output_path.resolve():
		raise RepairError("source and output must be different paths")
	if not source_path.is_file():
		raise RepairError(f"source does not exist: {source_path}")
	if output_path.exists():
		raise RepairError(f"refusing to overwrite existing output: {output_path}")
	output_path.parent.mkdir(parents=True, exist_ok=True)
	result: dict[str, int] = {}
	raw_temp: Path | None = None
	try:
		with tempfile.NamedTemporaryFile(
				prefix=f".{output_path.name}.", suffix=".raw", dir=output_path.parent,
				delete=False) as raw_output:
			raw_temp = Path(raw_output.name)
			with source_path.open("rb") as compressed_source:
				reader = ZlibReader(compressed_source)
				root_type = reader.exact(1)
				if root_type != b"\x0a":
					raise RepairError(f"root tag type is {root_type[0]}, expected compound")
				raw_output.write(root_type)
				copy_string(reader, raw_output)
				copy_compound(reader, raw_output, root=True, marker=marker, result=result)
				reader.finish()
				result["source_raw_bytes"] = reader.uncompressed_bytes
			raw_output.flush()
			os.fsync(raw_output.fileno())
		if "original_entities" not in result:
			raise RepairError("root NBT has no Entities list")
		if expected_remove is not None and result["removed_entities"] != expected_remove:
			raise RepairError(
				f"expected to remove {expected_remove}, found {result['removed_entities']}"
			)
		if expected_keep is not None and result["kept_entities"] != expected_keep:
			raise RepairError(
				f"expected to keep {expected_keep}, found {result['kept_entities']}"
			)
		raw_bytes, compressed_bytes, raw_sha256 = compress_raw(raw_temp, output_path)
		verify_compressed(output_path, marker, raw_bytes, raw_sha256)
		return {
			"source": str(source_path),
			"source_sha256": sha256_file(source_path),
			"source_compressed_bytes": source_path.stat().st_size,
			**result,
			"output": str(output_path),
			"output_sha256": sha256_file(output_path),
			"output_compressed_bytes": compressed_bytes,
			"output_raw_bytes": raw_bytes,
			"output_raw_sha256": raw_sha256,
			"status": "VERIFIED_REPAIR_OUTPUT",
		}
	except Exception:
		output_path.unlink(missing_ok=True)
		raise
	finally:
		if raw_temp is not None:
			raw_temp.unlink(missing_ok=True)


def nbt_string(value: bytes) -> bytes:
	return struct.pack(">H", len(value)) + value


def string_tag(name: bytes, value: bytes) -> bytes:
	return b"\x08" + nbt_string(name) + nbt_string(value)


def sample_entity(marker: bytes | None) -> bytes:
	payload = string_tag(b"id", b"minecraft:text_display")
	if marker is not None:
		payload += b"\x0a" + nbt_string(b"BukkitValues")
		payload += string_tag(marker, b"info") + b"\x00"
	return payload + b"\x00"


def self_test() -> None:
	with tempfile.TemporaryDirectory() as directory:
		root = Path(directory)
		source = root / "source.mcc"
		output = root / "output.mcc"
		entities = sample_entity(DEFAULT_MARKER) + sample_entity(b"rookieairdrops:airdrop_type")
		raw = b"\x0a\x00\x00" + b"\x09" + nbt_string(b"Entities")
		raw += b"\x0a" + struct.pack(">i", 2) + entities + b"\x00"
		source.write_bytes(zlib.compress(raw, level=9))
		result = repair(source, output, DEFAULT_MARKER, 1, 1)
		plain = zlib.decompress(output.read_bytes())
		if DEFAULT_MARKER in plain or b"rookieairdrops:airdrop_type" not in plain:
			raise RepairError("self-test did not preserve only the unrelated entity")
		print(json.dumps(result, indent=2, ensure_ascii=False))


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("source", nargs="?", type=Path)
	parser.add_argument("output", nargs="?", type=Path)
	parser.add_argument("--marker", default=DEFAULT_MARKER.decode())
	parser.add_argument("--expect-remove", type=int)
	parser.add_argument("--expect-keep", type=int)
	parser.add_argument("--self-test", action="store_true")
	args = parser.parse_args()
	if args.self_test:
		self_test()
		return
	if args.source is None or args.output is None:
		parser.error("source and output are required unless --self-test is used")
	result = repair(
		args.source,
		args.output,
		args.marker.encode(),
		args.expect_remove,
		args.expect_keep,
	)
	print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
	main()
