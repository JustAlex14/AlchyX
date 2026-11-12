// The Egg Hatchery's recipe takes an egg and gives nothing: this script decides, as the operation starts, which
// spawn egg it will give. The spawner on top shows that mob turning inside it while the egg hatches, the "result"
// display (the item anchors that float "output"/"result") shows the spawn egg, and the same egg comes out at the end.
// Slots: inputs first (slot 0: the egg), then outputs (slot 1: the spawn egg).

const HATCHERY = 'mypack:egg_hatchery_core'
const OUTPUT_SLOT = 1
const SPAWNER = 'spawner' // the pattern's anchor on the spawner (see data/mypack/multiblock/egg_hatchery.json)
// Never these (bosses, and things that make no sense out of an egg)
const NEVER = ['minecraft:ender_dragon_spawn_egg', 'minecraft:wither_spawn_egg', 'minecraft:elder_guardian_spawn_egg',
  'minecraft:warden_spawn_egg']
// If the item list can't be read (another KubeJS version), these vanilla ones
const FALLBACK = ['minecraft:chicken_spawn_egg', 'minecraft:cow_spawn_egg', 'minecraft:pig_spawn_egg',
  'minecraft:sheep_spawn_egg', 'minecraft:rabbit_spawn_egg', 'minecraft:fox_spawn_egg', 'minecraft:wolf_spawn_egg',
  'minecraft:cat_spawn_egg', 'minecraft:parrot_spawn_egg', 'minecraft:bee_spawn_egg', 'minecraft:frog_spawn_egg',
  'minecraft:axolotl_spawn_egg', 'minecraft:zombie_spawn_egg', 'minecraft:skeleton_spawn_egg',
  'minecraft:creeper_spawn_egg', 'minecraft:spider_spawn_egg', 'minecraft:slime_spawn_egg', 'minecraft:blaze_spawn_egg',
  'minecraft:witch_spawn_egg', 'minecraft:villager_spawn_egg']

let eggs = null

// Every spawn egg in the game, modded ones too (any item whose id ends in "_spawn_egg"), once
function spawnEggs() {
  if (eggs != null) return eggs
  try {
    eggs = Ingredient.of(/_spawn_egg$/).itemIds.toArray().map(id => String(id)).filter(id => !NEVER.includes(id))
  } catch (e) {
    console.warn(`Egg Hatchery: can't list the spawn eggs (${e}), using a short list`)
    eggs = []
  }
  if (eggs.length == 0) eggs = FALLBACK
  return eggs
}

function randomEgg() {
  let list = spawnEggs()
  return list[Math.floor(Math.random() * list.length)]
}

// "minecraft:chicken_spawn_egg" -> "minecraft:chicken" (most mods name their eggs the same way)
function mobOf(eggId) {
  return eggId.replace(/_spawn_egg$/, '')
}

AlchyXEvents.operationStarting(HATCHERY, event => {
  // Wait until the last spawn egg was taken out: two different eggs can't share the slot
  if (!event.machine.getItem(OUTPUT_SLOT).isEmpty()) {
    event.cancel()
    return
  }
  // Pick the egg now, so the spawner and the floating egg show it while it hatches
  let egg = randomEgg()
  event.machine.setDisplay('result', Item.of(egg))
  event.machine.setSpawnerMob(SPAWNER, mobOf(egg))
})

AlchyXEvents.operationFinished(HATCHERY, event => {
  // The egg picked as it started (a random one if the game restarted in between)
  let shown = event.machine.getDisplay('result')
  let egg = shown.isEmpty() ? randomEgg() : String(shown.id)
  event.machine.setItem(OUTPUT_SLOT, Item.of(egg))
  // The spawner keeps showing the last mob until the next egg starts
})
