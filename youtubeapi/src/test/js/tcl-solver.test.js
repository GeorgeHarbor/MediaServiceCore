// Run with node youtubeapi/src/test/js/tcl-solver.test.js from MediaServiceCore.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const assets = path.resolve(__dirname, '../../main/assets/nsigsolver');
const context = vm.createContext({});
vm.runInContext('var meriyah = null, astring = null;', context);
for (const file of ['polyfill.js', 'tcl.solver.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets, file), 'utf8'), context);
}
function solve(code, challenge) {
    context.input = {type: 'preprocessed', preprocessed_player: code, challenges: [challenge]};
    const output = vm.runInContext('tclSolver(input)', context);
    assert.strictEqual(output.type, 'result', output.error);
    return output.results[challenge];
}
// Count compilation in the generated code, and check the cache follows player rotations.
const first = 'globalThis.compiles = (globalThis.compiles || 0) + 1; return function(v) { return v + "-one"; };';
const second = 'globalThis.compiles++; return function(v) { return v + "-two"; };';
assert.strictEqual(solve(first, 'a'), 'a-one');
assert.strictEqual(solve(first, 'b'), 'b-one');
assert.strictEqual(context.compiles, 1);
assert.strictEqual(solve(second, 'a'), 'a-two');
assert.strictEqual(context.compiles, 2);
assert.strictEqual(solve(first, 'c'), 'c-one');
assert.strictEqual(context.compiles, 3);
console.log('Decoder reuse and player rotation checks passed without AST libraries.');
