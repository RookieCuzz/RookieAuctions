#!/usr/bin/env python3
"""Inspect one Minecraft entity chunk stored in an Anvil region file."""

from __future__ import annotations

import argparse
from io import BytesIO
import json
from pathlib import Path
import struct
import zlib

from repair_external_entity_chunk import (
	DEFAULT_MARKER,
	RepairError,
	ZlibReader,
	copy_compound,
	copy_string,
)


def as_zlib_stream(payload: bytes, compression_type: int) -> bytes:
	if compression_type == 2:
		return payload
	if compression_type == 1:
		return zlib.compress(zlib.decompress(payload, wbits=31))
	if compression_type == 3:
		return zlib.compress(payload)
	raise RepairError(f"unsupported region compression type {compression_type}")


def inspect(region_path: Path, chunk_x: int, chunk_z: int, marker: bytes) -> dict[str, object]:
	if not region_path.is_file():
		raise RepairError(f"region does not exist: {region_path}")

	index = (chunk_x & 31) + ((chunk_z & 31) * 32)
	with region_path.open("rb") as region:
		region.seek(index * 4)
		location = region.read(4)
		if len(location) != 4:
			raise RepairError("truncated Anvil location table")
		sector_offset = int.from_bytes(location[:3], "big")
		sector_count = location[3]
		if sector_offset == 0 or sector_count == 0:
			raise RepairError(f"chunk {chunk_x},{chunk_z} is absent from {region_path}")

		region.seek(sector_offset * 4096)
		raw_length = region.read(4)
		if len(raw_length) != 4:
			raise RepairError("truncated Anvil chunk length")
		stored_length = struct.unpack(">I", raw_length)[0]
		if stored_length < 1:
			raise RepairError(f"invalid Anvil chunk length {stored_length}")
		compression_byte = region.read(1)
		if len(compression_byte) != 1:
			raise RepairError("missing Anvil compression byte")
		compression = compression_byte[0] & 0x7F
		external = bool(compression_byte[0] & 0x80)
		payload = region.read(stored_length - 1)
		if len(payload) != stored_length - 1:
			raise RepairError("truncated inline Anvil payload")

	storage = "external" if external else "inline"
	data_path = region_path
	if external:
		data_path = region_path.parent / f"c.{chunk_x}.{chunk_z}.mcc"
		if not data_path.is_file():
			raise RepairError(f"external entity payload is missing: {data_path}")
		payload = data_path.read_bytes()

	zlib_stream = as_zlib_stream(payload, compression)
	reader = ZlibReader(BytesIO(zlib_stream))
	root_type = reader.exact(1)
	if root_type != b"\x0a":
		raise RepairError(f"root tag type is {root_type[0]}, expected compound")
	sink = BytesIO()
	sink.write(root_type)
	copy_string(reader, sink)
	result: dict[str, int] = {}
	copy_compound(reader, sink, root=True, marker=marker, result=result)
	reader.finish()
	if "original_entities" not in result:
		raise RepairError("root NBT has no Entities list")

	return {
		"region": str(region_path),
		"chunk_x": chunk_x,
		"chunk_z": chunk_z,
		"storage": storage,
		"data_path": str(data_path),
		"sector_offset": sector_offset,
		"sector_count": sector_count,
		"compression_type": compression,
		"compressed_bytes": len(payload),
		"raw_bytes": reader.uncompressed_bytes,
		"entities": result["original_entities"],
		"marker_entities": result["removed_entities"],
		"other_entities": result["kept_entities"],
		"status": "VERIFIED_ENTITY_CHUNK",
	}


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("region", type=Path)
	parser.add_argument("chunk_x", type=int)
	parser.add_argument("chunk_z", type=int)
	parser.add_argument("--marker", default=DEFAULT_MARKER.decode())
	args = parser.parse_args()
	print(json.dumps(
		inspect(args.region, args.chunk_x, args.chunk_z, args.marker.encode()),
		indent=2,
		sort_keys=True,
	))


if __name__ == "__main__":
	main()
