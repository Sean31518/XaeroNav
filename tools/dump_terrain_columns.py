#!/usr/bin/env python3
"""Writes terrain fixtures for tests from Minecraft world save data (.mca).

Output is in the format read by `TerrainFixture` (src/test/java/.../world/TerrainFixture.java):

    minX minY minZ maxX maxY maxZ
    x z <kind><fromY>,<toY> <kind><fromY>,<toY> ...

The kind is a one-character symbol from `FakeCells`. If omitted (starts with a digit), it's read as stone,
so fixtures written before kinds existed can still be read as-is.

Usage:
    python3 tools/dump_terrain_columns.py <region-dir> <minX> <minZ> <maxX> <maxZ> \
        --band <low>,<high> --out src/test/resources/<name>.txt.gz
"""
import argparse
import gzip
import math
import os
import struct
import zlib

STONE, SOFT, WATER, LAVA, VINE, LADDER, WALL = '#', 'D', '~', 'L', 'V', 'H', 'B'

# Blocks that can be passed through (written as air). Includes grass, flowers, torches and the like;
# writing them as solid would turn the whole surface into terrain you can't cross without digging
PASSABLE_SUFFIXES = (
    '_air', 'air', 'grass', 'fern', 'flower', 'tulip', 'orchid', 'bluet', 'daisy', 'rose',
    'poppy', 'dandelion', 'cornflower', 'lily_of_the_valley', 'allium', 'sapling', 'torch',
    'sign', 'button', 'lever', 'rail', 'pressure_plate', 'snow', 'carpet', 'mushroom',
    'seagrass', 'kelp', 'kelp_plant', 'sugar_cane', 'dead_bush', 'sweet_berry_bush',
    'cobweb', 'sculk_vein', 'glow_lichen', 'nether_sprouts', 'wither_rose', 'fire',
    'soul_fire', 'vine_end',
)
PASSABLE_EXACT = {
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:short_grass',
    'minecraft:tall_grass', 'minecraft:large_fern', 'minecraft:snow', 'minecraft:light',
    'minecraft:structure_void', 'minecraft:moving_piston', 'minecraft:tripwire',
    'minecraft:nether_portal', 'minecraft:end_portal', 'minecraft:end_gateway',
}
# Values are (symbol, whether to exclude it as a reference for ground height)
CLIMBABLE = {'minecraft:ladder': (LADDER, False), 'minecraft:vine': (VINE, True),
             'minecraft:weeping_vines': (VINE, True), 'minecraft:weeping_vines_plant': (VINE, True),
             'minecraft:twisting_vines': (VINE, True), 'minecraft:twisting_vines_plant': (VINE, True),
             'minecraft:cave_vines': (VINE, True), 'minecraft:cave_vines_plant': (VINE, True),
             'minecraft:scaffolding': (LADDER, False)}

# Written as undiggable walls. The real `DiggableBlocks` is allowlist-based and doesn't allow logs or stems
LOG_SUFFIXES = ('_log', '_wood', '_stem', '_hyphae')
# Ends in `_stem` but is diggable in the real game
NOT_LOG = {'minecraft:mushroom_stem'}
SOFT_BLOCKS = {
    'minecraft:dirt', 'minecraft:grass_block', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt',
    'minecraft:podzol', 'minecraft:mycelium', 'minecraft:sand', 'minecraft:red_sand',
    'minecraft:gravel', 'minecraft:clay', 'minecraft:soul_sand', 'minecraft:soul_soil',
    'minecraft:snow_block', 'minecraft:mud', 'minecraft:farmland', 'minecraft:dirt_path',
    'minecraft:moss_block', 'minecraft:sculk', 'minecraft:netherrack', 'minecraft:crimson_nylium',
    'minecraft:warped_nylium', 'minecraft:soul_sand',
    'minecraft:mangrove_roots', 'minecraft:muddy_mangrove_roots',
}


def classify(name):
    """Returns (symbol, whether to exclude it as a reference for ground height). If the symbol is None, it isn't written as air.

    The second value only affects where `--depth` measures depth from. Trees, vines, and bamboo grow on top of the ground,
    so they can't be the reference, and water and lava must be measured from the terrain below rather than the surface or the seabed gets cut off.
    """
    if name in {'minecraft:bedrock', 'minecraft:barrier'}:
        return WALL, False
    if name in PASSABLE_EXACT:
        return None, False
    short = name.split(':', 1)[1] if ':' in name else name
    if short.endswith(PASSABLE_SUFFIXES) and 'block' not in short:
        return None, False
    if name in CLIMBABLE:
        return CLIMBABLE[name]
    if name == 'minecraft:water' or short.endswith('_water'):
        return WATER, True
    if name == 'minecraft:lava':
        return LAVA, True
    # Leaves and bamboo are "solids you dig through" in the real game. Discarding them as air removes the jungle canopy from the terrain
    if short.endswith('leaves'):
        return SOFT, True
    if name == 'minecraft:bamboo':
        return SOFT, True
    if name not in NOT_LOG and short.endswith(LOG_SUFFIXES):
        return WALL, True
    if name in SOFT_BLOCKS:
        return SOFT, False
    return STONE, False


class Nbt:
    """A minimal NBT reader that reads only the tags it needs."""

    def __init__(self, data):
        self.d = data
        self.i = 0

    def u1(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def raw(self, fmt, size):
        v = struct.unpack_from(fmt, self.d, self.i)[0]
        self.i += size
        return v

    def name(self):
        length = self.raw('>H', 2)
        s = self.d[self.i:self.i + length].decode('utf-8', 'replace')
        self.i += length
        return s

    def value(self, tag):
        if tag == 1:
            return self.raw('>b', 1)
        if tag == 2:
            return self.raw('>h', 2)
        if tag == 3:
            return self.raw('>i', 4)
        if tag == 4:
            return self.raw('>q', 8)
        if tag == 5:
            return self.raw('>f', 4)
        if tag == 6:
            return self.raw('>d', 8)
        if tag == 7:
            n = self.raw('>i', 4)
            v = self.d[self.i:self.i + n]
            self.i += n
            return v
        if tag == 8:
            return self.name()
        if tag == 9:
            item = self.u1()
            n = self.raw('>i', 4)
            return [self.value(item) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                child = self.u1()
                if child == 0:
                    return out
                # Read the name first. Writing `out[self.name()] = self.value(child)` makes Python
                # evaluate the right-hand side first, which swaps the value and the name
                key = self.name()
                out[key] = self.value(child)
        if tag == 11:
            n = self.raw('>i', 4)
            v = struct.unpack_from('>%di' % n, self.d, self.i)
            self.i += 4 * n
            return list(v)
        if tag == 12:
            n = self.raw('>i', 4)
            v = struct.unpack_from('>%dq' % n, self.d, self.i)
            self.i += 8 * n
            return list(v)
        raise ValueError('unknown tag %d' % tag)

    def root(self):
        tag = self.u1()
        self.name()
        return self.value(tag)


def read_chunk(region, cx, cz):
    header_index = 4 * ((cx & 31) + (cz & 31) * 32)
    entry = struct.unpack_from('>I', region, header_index)[0]
    if entry == 0:
        return None
    offset = (entry >> 8) * 4096
    length = struct.unpack_from('>I', region, offset)[0]
    compression = region[offset + 4]
    payload = region[offset + 5:offset + 4 + length]
    if compression == 1:
        payload = gzip.decompress(payload)
    elif compression == 2:
        payload = zlib.decompress(payload)
    else:
        # 3 (uncompressed) and 4 (LZ4, some 1.20.5+ settings) aren't supported. Without rejecting them here,
        # it would try to read the still-compressed bytes as raw NBT and fail confusingly
        # (if the first byte happens to match a valid tag ID, it doesn't even error and writes wrong terrain)
        raise ValueError('unsupported chunk compression type: %d' % compression)
    return Nbt(payload).root()


def section_overlaps_band(section_y, band_low, band_high):
    """Whether this section (a 16-block cube; `Y` is the section coordinate since 1.18 and can be negative)
    overlaps `[band_low, band_high]`."""
    base_y = section_y * 16
    return base_y <= band_high and base_y + 15 >= band_low


def section_blocks(section):
    """Returns one section's (16^3) block names in y*256+z*16+x order. A single string if they're all the same."""
    states = section.get('block_states')
    if states is None:
        return None
    palette = [entry['Name'] for entry in states['palette']]
    data = states.get('data')
    if not data or len(palette) == 1:
        return palette[0]
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for packed in data:
        packed &= 0xFFFFFFFFFFFFFFFF
        for slot in range(per_long):
            if len(out) == 4096:
                break
            out.append(palette[(packed >> (slot * bits)) & mask])
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('region_dir')
    parser.add_argument('min_x', type=int)
    parser.add_argument('min_z', type=int)
    parser.add_argument('max_x', type=int)
    parser.add_argument('max_z', type=int)
    parser.add_argument('--band', required=True, help='Y range to write: "low,high"')
    parser.add_argument('--depth', type=int, default=0,
                        help='For each column, write only down to this depth from the top of the ground (0 for unlimited). '
                             'If you only look at paths walking on the surface, writing all the way down to bedrock just bloats the file. '
                             'Trees, vines, and bamboo are not used as the reference and are always kept as-is')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()

    band_low, band_high = (int(v) for v in args.band.split(','))
    columns = {}
    for region_x in range(args.min_x >> 9, (args.max_x >> 9) + 1):
        for region_z in range(args.min_z >> 9, (args.max_z >> 9) + 1):
            path = os.path.join(args.region_dir, 'r.%d.%d.mca' % (region_x, region_z))
            if not os.path.exists(path):
                continue
            region = open(path, 'rb').read()
            for cx in range(region_x * 32, region_x * 32 + 32):
                for cz in range(region_z * 32, region_z * 32 + 32):
                    if cx * 16 > args.max_x or cx * 16 + 15 < args.min_x:
                        continue
                    if cz * 16 > args.max_z or cz * 16 + 15 < args.min_z:
                        continue
                    chunk = read_chunk(region, cx, cz)
                    if chunk is None or chunk.get('Status') not in (
                            'minecraft:full', 'full'):
                        continue
                    for section in chunk.get('sections', []):
                        if not section_overlaps_band(section['Y'], band_low, band_high):
                            continue
                        base_y = section['Y'] * 16
                        blocks = section_blocks(section)
                        if blocks is None:
                            continue
                        uniform = classify(blocks) if isinstance(blocks, str) else None
                        if uniform is not None and uniform[0] is None:
                            continue
                        for local_y in range(16):
                            y = base_y + local_y
                            if y < band_low or y > band_high:
                                continue
                            for local_z in range(16):
                                z = cz * 16 + local_z
                                if z < args.min_z or z > args.max_z:
                                    continue
                                for local_x in range(16):
                                    x = cx * 16 + local_x
                                    if x < args.min_x or x > args.max_x:
                                        continue
                                    if uniform is not None:
                                        kind, not_ground = uniform
                                    else:
                                        kind, not_ground = classify(
                                            blocks[local_y * 256 + local_z * 16 + local_x])
                                    if kind is None:
                                        continue
                                    columns.setdefault((x, z), []).append((y, kind, not_ground))

    lines = []
    min_y, max_y = band_high, band_low
    for (x, z), cells in sorted(columns.items()):
        cells.sort()
        if args.depth > 0:
            # The reference is the top of the ground. Measuring from the column's topmost block would cut away the ground under trees and vines;
            # in jungles, where leaves are written as solid, that removes the ground from 10% of the columns
            ground = next((y for y, _, not_ground in reversed(cells) if not not_ground),
                          cells[-1][0])
            cells = [c for c in cells if c[0] >= ground - args.depth]
        runs = []
        run_from, run_to, run_kind = cells[0][0], cells[0][0], cells[0][1]
        for y, kind, _ in cells[1:]:
            if y == run_to + 1 and kind == run_kind:
                run_to = y
                continue
            runs.append((run_kind, run_from, run_to))
            run_from, run_to, run_kind = y, y, kind
        runs.append((run_kind, run_from, run_to))
        min_y = min(min_y, runs[0][1])
        max_y = max(max_y, runs[-1][2])
        lines.append('%d %d %s' % (x, z, ' '.join(
            '%s%d,%d' % (kind, low, high) for kind, low, high in runs)))

    header = '%d %d %d %d %d %d' % (args.min_x - 16, min_y - 8, args.min_z - 16,
                                    args.max_x + 16, max_y + 24, args.max_z + 16)
    with gzip.open(args.out, 'wt', encoding='utf-8') as out:
        out.write(header + '\n')
        out.write('\n'.join(lines) + '\n')
    print('%s columns=%d Y=%d..%d %.1fKB' % (args.out, len(columns), min_y, max_y,
                                        os.path.getsize(args.out) / 1024.0))


if __name__ == '__main__':
    main()
