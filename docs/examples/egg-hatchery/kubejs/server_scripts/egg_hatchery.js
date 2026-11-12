// The Egg Hatchery's recipe takes an egg and gives nothing: this script puts a random spawn egg in its output slot.
// Slots: inputs first (slot 0: the egg), then outputs (slot 1: the spawn egg).

const HATCHERY = 'mypack:egg_hatchery_core'
const OUTPUT_SLOT = 1
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

// Wait until the last spawn egg was taken out: two different eggs can't share the slot
AlchyXEvents.operationStarting(HATCHERY, event => {
  if (!event.machine.getItem(OUTPUT_SLOT).isEmpty()) event.cancel()
})

AlchyXEvents.operationFinished(HATCHERY, event => {
  let list = spawnEggs()
  let id = list[Math.floor(Math.random() * list.length)]
  event.machine.setItem(OUTPUT_SLOT, Item.of(id))
})
