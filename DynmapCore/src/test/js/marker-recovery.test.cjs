const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Drive the shipped component with controlled requests, visibility and Leaflet layers.
function harness() {
    const handlers = new Map(), requests = [], timers = new Map(); let timerId = 0;
    function events(target) { if (!handlers.has(target)) handlers.set(target, {}); return handlers.get(target); }
    function $(target) { return {
        on(name, fn) { events(target)[name] = fn; return this; },
        trigger(name, args = []) { events(target)[name]?.call(target, {}, ...args); return this; },
        addClass() { return this; }, append() { return this; }, attr() { return this; }, html() { return ''; }
    }; }
    $.each = (items, fn) => Object.entries(items || {}).forEach(([key, value]) => fn(key, value));
    $.ajax = options => {
        const request = { options, done(fn) { this.resolve = fn; return this; }, fail(fn) { this.reject = fn; return this; }, abort() { this.aborted = true; this.reject?.(); } };
        requests.push(request); return request;
    };
    class Layer {
        constructor(points, options) { this.points = points; this.options = options; this.layers = new Set(); }
        addLayer(layer) { this.layers.add(layer); } removeLayer(layer) { this.layers.delete(layer); }
        bindPopup() {} setLatLng() {}
    }
    const document = { visibilityState: 'visible', createElement: () => ({}), addEventListener: (name, fn) => events(document)[name] = fn };
    const map = new Layer(); map.getZoom = () => 5;
    const selectors = new Map();
    const app = {world: {name: 'world'}, map, options: {url: {markers: '/tiles/'}},
        getProjection: () => ({fromLocationToLatLng: p => p}),
        addToLayerSelector: (layer, label) => selectors.set(layer, label), removeFromLayerSelector: layer => selectors.delete(layer)};
    const context = vm.createContext({$, document, componentconstructors: {}, concatURL: (a,b) => a+b,
        L: {LayerGroup: Layer, CustomMarker: Layer, Polygon: Layer, Polyline: Layer, LatLng: Layer},
        Location: function(world, x, y, z) { Object.assign(this, {world, x, y, z}); },
        setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id)});
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/extracted/web/js/markers.js'), 'utf8'), context);
    new context.componentconstructors.markers(app, {});
    return {app, requests, selectors, sets: context.dynmapmarkersets,
        message: msg => $(app).trigger('component.markers', [msg]),
        visibility(state) { document.visibilityState = state; events(document).visibilitychange(); },
        world(name) { app.world = {name}; $(app).trigger('worldchanged'); },
        retry() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); }};
}
const marker = x => ({label: '', x, y: 64, z: 0, icon: 'default', dim: '16x16'});
const layer = markers => ({label: 'Regions', markers, areas: {}, lines: {}, circles: {}});
const snapshot = (timestamp, sets) => ({timestamp, sets});

test('returning after expired deltas replaces markers and removes deleted layers', () => {
    const h = harness();
    h.requests[0].resolve(snapshot(1000, {regions: layer({old: marker(1)}), deleted: layer({ghost: marker(2)})}));
    const group = h.sets.regions.layergroup;
    h.app.map.removeLayer(group); // User's hidden-layer choice survives recovery.
    h.visibility('hidden'); h.visibility('visible');
    assert.equal(h.requests[1].options.cache, false);
    h.requests[1].resolve(snapshot(132000, {regions: layer({current: marker(9)})}));
    assert.equal(h.sets.deleted, undefined);
    assert.equal(h.sets.regions.markers.old, undefined);
    assert.equal(h.sets.regions.markers.current.x, 9);
    assert.equal(h.sets.regions.layergroup, group);
    assert.equal(h.app.map.layers.has(group), false);
    assert.equal(h.selectors.size, 1);
});

test('periodic snapshots cannot roll back deltas received before or during a request', () => {
    const h = harness(); h.requests[0].resolve(snapshot(100, {regions: layer({a: marker(1)})}));
    h.message({...marker(2), timestamp: 110, msg: 'markerupdated', set: 'regions', id: 'a'});
    h.visibility('visible');
    h.message({...marker(3), timestamp: 120, msg: 'markerupdated', set: 'regions', id: 'b'});
    h.message({timestamp: 130, msg: 'markerdeleted', set: 'regions', id: 'a'});
    h.requests[1].resolve(snapshot(105, {regions: layer({a: marker(1)})}));
    assert.equal(h.sets.regions.markers.a, undefined);
    assert.equal(h.sets.regions.markers.b.x, 3);
    h.message({...marker(0), timestamp: 104, msg: 'markerupdated', set: 'regions', id: 'b'});
    assert.equal(h.sets.regions.markers.b.x, 3);
});

test('old-world responses and aborted requests cannot replace the current world', () => {
    const h = harness(); const old = h.requests[0]; h.world('nether');
    assert(old.aborted); h.requests[1].resolve(snapshot(200, {nether: layer({a: marker(2)})}));
    old.resolve(snapshot(300, {overworld: layer({a: marker(1)})}));
    assert.equal(h.sets.overworld, undefined); assert.equal(h.sets.nether.markers.a.x, 2);
});

test('failed, malformed and older responses preserve layers and retry', () => {
    const h = harness(); h.requests[0].resolve(snapshot(100, {regions: layer({a: marker(1)})}));
    h.visibility('visible'); h.requests[1].reject();
    assert.equal(h.sets.regions.markers.a.x, 1); h.retry();
    h.requests[2].resolve({sets: {}}); assert.equal(h.sets.regions.markers.a.x, 1); h.retry();
    h.requests[3].resolve(snapshot(99, {})); assert.equal(h.sets.regions.markers.a.x, 1); h.retry();
    h.requests[4].resolve(snapshot(200, {})); assert.equal(h.sets.regions, undefined);
});

test('set deletion during recovery removes its geometry and selector', () => {
    const h = harness(); h.requests[0].resolve(snapshot(100, {regions: layer({a: marker(1)})}));
    h.visibility('visible'); h.message({timestamp: 120, msg: 'setdeleted', id: 'regions'});
    h.requests[1].resolve(snapshot(110, {regions: layer({a: marker(1)})}));
    assert.equal(h.sets.regions, undefined); assert.equal(h.selectors.size, 0); assert.equal(h.app.map.layers.size, 0);
});

test('bounded delta history waits for a snapshot covering discarded updates', () => {
    const h = harness(); h.requests[0].resolve(snapshot(100, {}));
    for (let i = 0; i < 4200; i++) h.message({timestamp: 200+i, msg: 'setdeleted', id: 'gone'});
    h.visibility('visible'); h.requests[1].resolve(snapshot(200, {obsolete: layer({})}));
    assert.equal(h.sets.obsolete, undefined); h.retry();
    h.requests[2].resolve(snapshot(5000, {current: layer({})})); assert(h.sets.current);
});

test('new layers initialize all geometry collections before later events', () => {
    const h = harness(); h.requests[0].resolve(snapshot(100, {}));
    h.message({timestamp: 110, msg: 'setupdated', id: 'new', label: 'New'});
    for (const collection of ['markers','areas','lines','circles']) assert(h.sets.new[collection]);
    h.message({timestamp: 120, msg: 'setdeleted', id: 'new'}); assert.equal(h.selectors.size, 0);
});

test('a poll response from the previous world cannot advance the current world timestamp', () => {
    let callback, scheduled = 0, events = 0;
    function $() { return {trigger() { events++; }}; }
    $.getJSON = (url, fn) => { callback = fn; return {fail() { return this; }}; };
    const context = vm.createContext({$, document: {visibilityState: 'visible'}, setTimeout: () => scheduled++});
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/extracted/web/js/map.js'), 'utf8'), context);
    const app = Object.create(context.DynMap.prototype);
    Object.assign(app, {world: {name: 'world'}, options: {updaterate: 1000}, lasttimestamp: 42, reqid: 9, formatUrl: () => '/update'});
    app.update(); app.world = {name: 'nether'};
    callback({timestamp: 1000, updates: [{type: 'component', ctype: 'markers'}]});
    assert.equal(app.lasttimestamp, 42); assert.equal(app.reqid, 9);
    assert.equal(scheduled, 1); assert.equal(events, 1);
});

test('filled region footprints have one ring so interior clicks cannot fall through stacked holes', () => {
    const h = harness();
    const regions = layer({});
    regions.areas.spawn = {label: 'Spawn', desc: 'Owners: Tommy10606',
        x: [0, 10, 10, 0], z: [0, 0, 10, 10], ytop: 64, ybottom: 64,
        color: '#3388ff', opacity: 0.85, weight: 2, fillcolor: '#3388ff', fillopacity: 0.12};
    h.requests[0].resolve(snapshot(1000, {regions}));
    const polygon = h.sets.regions.areas.spawn.our_layer;
    assert.equal(polygon.points.length, 4);
    assert.ok(polygon.points.every(point => point.y === 64));
    assert.ok(polygon.options.fillOpacity > 0);
});
