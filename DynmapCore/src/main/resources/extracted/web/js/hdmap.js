// Modified for MCCMap: independent Paper fork (2026).
var HDProjection = DynmapProjection.extend({
	fromLocationToLatLng: function(location) {
		var wtp = this.options.worldtomap;
			lat = wtp[3] * location.x + wtp[4] * location.y + wtp[5] * location.z,
			lng = wtp[0] * location.x + wtp[1] * location.y + wtp[2] * location.z;

		return new L.LatLng(
			  -(((128 << this.options.tilescale) - lat) / (1 << this.options.mapzoomout))
			, lng / (1 << this.options.mapzoomout)
			, location.y);
	},
	fromLatLngToLocation: function(latlon, y) {
		var ptw = this.options.maptoworld,
			lat = (128 << this.options.tilescale) + latlon.lat * (1 << this.options.mapzoomout),
			lng = latlon.lng * (1 << this.options.mapzoomout),
			x = ptw[0] * lng + ptw[1] * lat + ptw[2] * y,
			z = ptw[6] * lng + ptw[7] * lat + ptw[8] * y;
		
		return { x: x, y: y, z: z };
	}
});

var HDMapType = DynmapTileLayer.extend({
	projection: undefined,
	options: {
		minZoom: 0,
		errorTileUrl: 'images/blank.png',
		zoomReverse: true,
	},
	initialize: function(options) {
		var outMultiplier = Number(options.zoomoutmultiplier === undefined ? 1.5 : options.zoomoutmultiplier);
		var inMultiplier = Number(options.zoominmultiplier === undefined ? 1.25 : options.zoominmultiplier);
		if (!Number.isFinite(outMultiplier) || outMultiplier < 1 || outMultiplier > 16) outMultiplier = 1.5;
		if (!Number.isFinite(inMultiplier) || inMultiplier < 1 || inMultiplier > 16) inMultiplier = 1.25;
		options.minViewZoom = outMultiplier === 1 ? 0 : -Math.log2(outMultiplier);
		options.minZoom = Math.floor(options.minViewZoom);
		options.minNativeZoom = 0;
		options.maxViewZoom = options.mapzoomin + options.mapzoomout + Math.log2(inMultiplier);
        var pixels = Number(options.maxpixelsperblock === undefined ? 16 : options.maxpixelsperblock);
        if (!Number.isFinite(pixels) || pixels < 0 || (pixels > 0 && pixels < 1) || pixels > 512) pixels = 16;
        var scale = Number(options.scale);
        if (pixels > 0 && Number.isFinite(scale) && scale > 0)
            options.maxViewZoom = Math.min(options.maxViewZoom, options.mapzoomout + Math.log2(pixels / scale));
		// Leaflet rounds tile levels before checking layer bounds. Keep these integral;
		// the map applies the exact fractional display limits separately.
		options.maxZoom = Math.ceil(options.maxViewZoom);
		options.maxNativeZoom = options.mapzoomout;
		options.tileSize = 128 << (options.tilescale || 0);

		this.projection = new HDProjection($.extend({map: this}, options));

		L.Util.setOptions(this, options);
		DynmapTileLayer.prototype.initialize.call(this, options);
	},
	updateBorderZoom: function(map) {
        var border = this.options.world && this.options.world.worldborder;
        if (!Array.isArray(border) || border.length < 3 || !map.getSize) return;
        var size = map.getSize(), points = border.map(function(p) {
            return this.projection.fromLocationToLatLng({x: p[0], y: this.options.world.sealevel || 64, z: p[1]});
        }, this);
        var xs = points.map(function(p) {return p.lng;}), ys = points.map(function(p) {return p.lat;});
        var width = Math.max.apply(null, xs) - Math.min.apply(null, xs), height = Math.max.apply(null, ys) - Math.min.apply(null, ys);
        var padding = Number(this.options.borderpadding);
        if (!Number.isFinite(padding) || padding < 1 || padding > 4) padding = 1.1;
        if (width <= 0 || height <= 0 || size.x <= 0 || size.y <= 0) return;
        var minimum = Math.log2(Math.min(size.x / width, size.y / height) / padding);
        this.options.minViewZoom = Math.max(-30, Math.min(this.options.maxViewZoom, minimum));
        this.options.minZoom = Math.floor(this.options.minViewZoom);
        map.options.minZoom = this.options.minViewZoom;
    },
	getTileName: function(coords) {
		var info = this.getTileInfo(coords);
		// Y is inverted for HD-map.
		info.y = -info.y;
		info.scaledy = info.y >> 5;
		return namedReplace('{prefix}{nightday}/{scaledx}_{scaledy}/{zoom}{x}_{y}.{fmt}', info);
	},
	zoomprefix: function(amount) {
		// amount == 0 -> ''
		// amount == 1 -> 'z_'
		// amount == 2 -> 'zz_'
		return 'zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz'.substr(0, amount) + (amount === 0 ? '' : '_');
	}
});

maptypes.HDMapType = function(options) { return new HDMapType(options); };
