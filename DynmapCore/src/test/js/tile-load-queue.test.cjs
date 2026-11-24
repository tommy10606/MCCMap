const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function harness() {
    const requests = [], document = {documentElement: {style: {}}, addEventListener() {},
        createElement() {
            const element = {style: {}, complete: false, setAttribute() {}, getContext: () => ({})};
            Object.defineProperty(element, 'src', {get() {return this.urlLoaded;}, set(url) {this.urlLoaded = url;requests.push({element, url});}});
            return element;
        }};
    const navigator = {userAgent: 'test', platform: 'Linux', maxTouchPoints: 0};
    const window = {document, navigator, screen: {}, devicePixelRatio: 1, addEventListener() {}, removeEventListener() {}};
    const context = vm.createContext({window, document, navigator, setTimeout, clearTimeout, console,
        $: Object.assign(() => ({}), {extend: Object.assign, fn: {}})});
    const root = path.join(__dirname, '../../main/resources/extracted/web/js');
    vm.runInContext(fs.readFileSync(path.join(root, 'leaflet.js'), 'utf8'), context);context.L = window.L;
    vm.runInContext(fs.readFileSync(path.join(root, 'mccmaputils.js'), 'utf8'), context);
    const layer = new context.DynmapTileLayer();
    layer.options.mccmap = {inittime: 1, getTileUrl: name => '/tiles/' + name};
    layer.getTileName = ({x, y, z}) => `${z}/${x}_${y}.png`;
    layer._tiles = {};layer._tileZoom = 6;
    function create(x, z = 6) {
        const coords = {x, y: 0, z}, element = layer.createTile(coords, () => {});
        layer._tiles[`${x}:${z}`] = {coords, el: element};return element;
    }
    return {layer, create, requests};
}
test('updates for aborted unfinished tiles cannot occupy all slots after zooming out', () => {
    const {layer, create} = harness(), old = Array.from({length: 5}, (_, x) => create(x));
    layer._tileZoom = 5;layer._abortLoading();
    for (const image of old) layer.updateNamedTile(image.tileName, 2);
    const visible = create(10, 5);
    assert.ok(visible.src, 'new zoom terrain request must start');
    for (const image of old) assert.equal(layer._namedTiles[image.tileName], undefined);
    assert.equal(layer._loadingTiles.length, 1);
});
test('rapid updates coalesce while loading and reload the latest timestamp once', () => {
    const {layer, create, requests} = harness(), image = create(0);
    for (let timestamp = 2; timestamp <= 20; timestamp++) layer.updateNamedTile(image.tileName, timestamp);
    assert.equal(layer._loadingTiles.length, 1);assert.equal(requests.length, 1);
    image.onload();
    assert.equal(requests.length, 2);assert.match(image.src, /timestamp=20$/);
    image.onload();assert.equal(layer._loadingTiles.length, 0);assert.equal(layer._loadQueue.length, 0);
});
test('cached completed tiles restore callbacks when a later update follows a zoom change', () => {
    const {layer, create} = harness(), image = create(0);image.onload();
    layer._tiles['0:6'].loaded = 1;image.complete = true;
    layer._tileZoom = 5;layer._abortLoading();
    layer.updateNamedTile(image.tileName, 2);assert.equal(layer._loadingTiles.length, 1);
    image.onload();assert.equal(layer._loadingTiles.length, 0);
});
test('late duplicate completion events do not remove another active request', () => {
    const {layer, create} = harness(), first = create(0), second = create(1);
    first.onload();first.onload();assert.equal(layer._loadingTiles.length, 1);assert.equal(layer._loadingTiles[0], second);
});
