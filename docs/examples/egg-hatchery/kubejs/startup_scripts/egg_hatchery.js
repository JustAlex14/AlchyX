// Egg Hatchery: an egg goes in, a random spawn egg comes out (see server_scripts/egg_hatchery.js).
// Slot 0: the egg. Slot 1: blaze powder, the heat (each one makes it 25% faster). Slot 2: the spawn egg.
StartupEvents.registry('block', event => {
  event.create('mypack:egg_hatchery_core', 'alchyx:machine_core')
    .displayName('Egg Hatchery Core')
    .pattern('mypack:egg_hatchery')
    .itemInputs(2)
    .itemOutputs(1)
    .texture('side', 'minecraft:block/polished_blackstone_bricks')
    .texture('top', 'minecraft:block/polished_blackstone_bricks')
    .texture('front', 'minecraft:block/spawner')
})
