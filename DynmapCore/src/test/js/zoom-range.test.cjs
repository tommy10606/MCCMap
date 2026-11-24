const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.join(__dirname, '../../main/resources/extracted/web/js');
function harness() {
    // Run the shipped Leaflet grid and URL code; only DOM rendering is stubbed.
    const document = {documentElement: {style: {}}, createElement: () => ({style: {}, getContext: () => ({})}), addEventListener() {}};
    const navigator = {userAgent: 'test', platform: 'Linux', maxTouchPoints: 0};
    const window = {document, navigator, screen: {}, devicePixelRatio: 1, addEventListener() {}, removeEventListener() {}};
    function $(target) {return {ready() {}, trigger() {}, addClass() {return this;}, removeClass() {return this;}};}
    Object.assign($, {extend: Object.assign, fn: {}});
    const context = vm.createContext({window, document, navigator, setTimeout, clearTimeout, console, maptypes: {}, $});
    vm.runInContext(fs.readFileSync(path.join(root, 'leaflet.js'), 'utf8'), context);
    context.L = window.L;
    for (const name of ['mccmaputils.js', 'hdmap.js', 'map.js'])
        vm.runInContext(fs.readFileSync(path.join(root, name), 'utf8'), context);
    return context;
}
function layer(options = {}, context = harness()) {
    return new context.HDMapType({mapzoomin: 2, mapzoomout: 6, prefix: 'surface',
        mccmap: {serverday: false, inittime: 1, getTileUrl: name => '/tiles/' + name}, ...options});
}
function view(layer, zoom) {
    for (const name of ['_abortLoading', '_updateLevels', '_resetGrid', '_update', '_pruneTiles', '_setZoomTransforms']) layer[name] = () => {};
    layer._setView({}, zoom);
    return layer._tileZoom;
}
test('default display extents retain 1.5 times distance and 1.25 times magnification', () => {
    const tileLayer = layer(), range = tileLayer.getViewZoomRange();
    assert.ok(Math.abs(2 ** -range.min - 1.5) < 1e-12);
    assert.ok(Math.abs(2 ** (range.max - 8) - 1.25) < 1e-12);
    assert.equal(tileLayer.options.minNativeZoom, 0); assert.equal(tileLayer.options.maxNativeZoom, 6);
    assert.equal(tileLayer.options.minZoom, -1); assert.equal(tileLayer.options.maxZoom, 9);
});
test('zoom range can be restored and invalid factors use safe defaults', () => {
    const range = layer({zoomoutmultiplier: 1, zoominmultiplier: 1}).getViewZoomRange();
    assert.equal(range.min, 0); assert.equal(range.max, 8);
    const invalid = layer({zoomoutmultiplier: 'bad', zoominmultiplier: 1000}).getViewZoomRange();
    assert.ok(Math.abs(2 ** -invalid.min - 1.5) < 1e-12);
    assert.ok(Math.abs(2 ** (invalid.max - 8) - 1.25) < 1e-12);
});
test('Leaflet retains native tiles at both fractional display limits and after repeated zoom changes', () => {
    for (const out of [1, 1.5, 2, 16]) for (const inside of [1, 1.25, 16]) {
        const tileLayer = layer({zoomoutmultiplier: out, zoominmultiplier: inside});
        const range = tileLayer.getViewZoomRange();
        for (const zoom of [6, 5, 2, 0, range.min, range.max, 4, range.min, 6]) {
            assert.equal(view(tileLayer, zoom), Math.max(0, Math.min(6, Math.round(zoom))), 'tile grid at zoom ' + zoom);
        }
    }
});
test('native tile URLs use the exact stored level and remain correct while panning at every zoom', () => {
    for (const inside of [1, 1.25, 2, 16]) {
        const tileLayer = layer({zoominmultiplier: inside});
        for (let z = 0; z <= 6; z++) {
            view(tileLayer, z);
            const level = 6 - z, scale = 2 ** level, prefix = level ? 'z'.repeat(level) + '_' : '';
            for (const [x, y] of [[0, 0], [1, 1], [-3, -2], [33, -35]]) {
                const tx = x * scale, ty = -y * scale;
                const expected = `surface/${tx >> 5}_${ty >> 5}/${prefix}${tx}_${ty}.png`;
                assert.equal(tileLayer.getTileName({x, y, z}), expected);
                assert.equal(tileLayer.getTileUrl({x, y, z}), '/tiles/' + expected + '?timestamp=1');
            }
        }
    }
});
test('map selection applies exact display limits and clamps the previous zoom in either direction', () => {
    const context = harness();
    for (const previous of [-5, 4, 12]) {
        const world = {}, tileLayer = layer({world}, context); let selected;
        const map = {options: {}, getZoom: () => previous, getCenter: () => ({}),
            setView: (center, zoom) => {selected = zoom;}, addLayer() {}};
        const app = {map, world, maptype: null, updateBackground() {}};
        context.DynMap.prototype.selectMapAndPan.call(app, tileLayer, null);
        const range = tileLayer.getViewZoomRange();
        assert.equal(map.options.minZoom, range.min); assert.equal(map.options.maxZoom, range.max);
        assert.equal(selected, Math.max(range.min, Math.min(range.max, previous)));
    }
});
test('a complete border fits with ten percent padding and safe native tile density', () => {
    const tileLayer = layer({mapzoomout: 11, world: {sealevel: 64, worldborder: [[-18000,-18000],[18000,-18000],[18000,18000],[-18000,18000]]},
        worldtomap: [4,0,0,0,0,-4], maptoworld: [0,0,0,0,0,0,0,0,0], borderpadding: 1.1});
    const map = {options: {}, getSize: () => ({x: 1000, y: 700})};tileLayer.updateBorderZoom(map);
    const range = tileLayer.getViewZoomRange(), span = 36000 * 4 / (2 ** 11);
    assert.ok(Math.abs(span * 2 ** range.min * 1.1 - 700) < 1e-8);
    assert.ok(view(tileLayer, range.min) >= 0);
    assert.ok((1000 / 2 ** Math.max(0,range.min)) / 128 < 10);
});
test('border fit recalculates for smaller viewports and leaves zoom-in detail intact', () => {
    const tileLayer = layer({world: {worldborder: [[0,0],[36000,0],[36000,36000],[0,36000]]},worldtomap: [4,0,0,0,0,-4]});
    const max = tileLayer.options.maxViewZoom;let width = 1000;
    const map = {options: {},getSize: () => ({x: width,y: width})};tileLayer.updateBorderZoom(map);
    const first = tileLayer.options.minViewZoom;width = 500;tileLayer.updateBorderZoom(map);
    assert.ok(Math.abs(first - tileLayer.options.minViewZoom - 1) < 1e-12);assert.equal(tileLayer.options.maxViewZoom,max);
});

test('screen magnification stops at sixteen pixels per block independently of stored resolution', () => {
    for (const scale of [4,8,16,32,64]) {
        const tileLayer=layer({scale,mapzoomin:5,mapzoomout:13,maxpixelsperblock:16});
        const max=tileLayer.getViewZoomRange().max;
        assert.ok(Math.abs(scale*2**(max-13)-16)<1e-10);
        assert.equal(tileLayer.options.maxNativeZoom,13);
    }
    const disabled=layer({scale:16,mapzoomin:5,mapzoomout:13,maxpixelsperblock:0});
    assert.ok(disabled.getViewZoomRange().max>18);
});
test('resolution changes preserve world coordinates for terrain, borders and markers', () => {
    const context=harness();
    function make(scale,native) {return layer({scale,mapzoomin:5,mapzoomout:native,worldtomap:[scale,0,0,0,0,-scale,0,1,0],maptoworld:[1/scale,0,0,0,0,1,0,-1/scale,0],maxpixelsperblock:16},context);}
    const high=make(16,13),balanced=make(8,12);
    for(const [x,z]of [[0,0],[-9000,4984],[-17999.5,-17999.5],[18000.5,18000.5],[127,-129]]) {
        const a=high.projection.fromLocationToLatLng({x,y:64,z});const b=balanced.projection.fromLocationToLatLng({x,y:64,z});
        assert.ok(Math.abs(a.lng-b.lng)<1e-10);
        // Leaflet's projected latitude includes one logical tile of vertical origin.
        assert.ok(Math.abs((a.lat+128/8192)-(b.lat+128/4096))<1e-10);
        const round=balanced.projection.fromLatLngToLocation(b,64);assert.ok(Math.abs(round.x-x)<1e-8&&Math.abs(round.z-z)<1e-8);
    }
});
