# Compatibility

Flowscope works with any block that exposes NeoForge's standard item or fluid storage. This page lists what has
actually been tested in game (All the Mods 11, Minecraft 26.1.2), so you know what to expect. Flowscope has no
mod-specific code; results depend on how each mod exposes and reports its storage.

| Mod | Blocks tested | Result | Notes |
|---|---|---|---|
| Vanilla | Chest, double chest, hopper, furnace | Works | Event-based. Furnace processing shows as Made/Used up. |
| Sophisticated Storage | Controller, chests | Works | |
| Functional Storage | Storage controller, drawers | Works | |
| EvilCraft | Dark Tank | Works | Fluids in mB. |
| Energized Power | Tanks | Works | |
| RFTools | Builder | Works | |
| Iron Furnaces | Furnaces with and without Factory Upgrade, Auto-Pull/Auto-Push | Works | Moves into/out of neighbouring blocks during the furnace's own tick are counted as Input/Output, also with infinite sources or voiding blocks. |
| Powah | Reactor | Works | |
| Refined Storage | Importer, Exporter | Works with notes | These blocks have no inventory of their own, so they can't be recorded directly. Record the chest/machine they connect to; their transfers show up there. |
| Oritech | Machines with core/extension blocks | Works with notes | Record the main machine block: processing is shown there. Recording a core block: fix for multiblock parts pending re-test. |
| Railcraft Reborn | Coke Oven | Limited | Recording a brick showed "Snapshot (approximate)" and the oven's products as Input instead of Made. Fix for multiblock parts pending re-test. |
| DimStorage | Dimensional Chest | Untested | Shared-frequency storage; fix for exact counting in progress. |
| Applied Energistics 2 | ME Interface, Pattern Provider | Untested | |
| Refined Storage | Interface | Untested | |
| Productive Metalworks | Foundry | Untested | |
| Railcraft Reborn | Iron/Steel Tank | Untested | |

## Known limits

- **Snapshot mode ("approximate"):** blocks that never report their changes are compared about once per second
  (every tick for small inventories). Items added and removed between two reads cancel out.
- **Pipes without their own storage:** if a machine pushes straight into such a pipe, the transfer can only be
  counted when the machine uses NeoForge's standard storage access (most do).
- **Hoppers picking up dropped items** count those items as Made.
- **Huge inventories** (more than 256 slots by default, e.g. very large storage networks) are refused instead of scanned.

Found something that doesn't work? Please open an issue with the mod, the block and a screenshot of the Flowscope window.
