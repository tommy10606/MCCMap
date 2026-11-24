var DynmapProjection = L.Class.extend({
	initialize: function(options) {
		L.Util.setOptions(this, options);
	},
	fromLocationToLatLng: function(location) {
		throw "fromLocationToLatLng not implemented";
	},
	fromLatLngToLocation: function(location) {
		return null;
	}
});

if (!Array.prototype.indexOf) { // polyfill for IE < 9
	    Array.prototype.indexOf = function (searchElement /*, fromIndex */ ) {
	        "use strict";
	        if (this === void 0 || this === null) {
	            throw new TypeError();
	        }
	        var t = Object(this);
	        var len = t.length >>> 0;
	        if (len === 0) {
	            return -1;
	        }
	        var n = 0;
	        if (arguments.length > 0) {
	            n = Number(arguments[1]);
	            if (n !== n) { // shortcut for verifying if it's NaN
	                n = 0;
	            } else if (n !== 0 && n !== (1 / 0) && n !== -(1 / 0)) {
	                n = (n > 0 || -1) * Math.floor(Math.abs(n));
	            }
	        }
	        if (n >= len) {
	            return -1;
	        }
	        var k = n >= 0 ? n : Math.max(len - Math.abs(n), 0);
	        for (; k < len; k++) {
	            if (k in t && t[k] === searchElement) {
	                return k;
	            }
	        }
	        return -1;
	    }
}

var DynmapLayerControl = L.Control.Layers.extend({
	getPosition: function() {
		return 'topleft';
	},
	
	// Function override to include pos
	addOverlay: function(layer, name, pos) {
		this._addLayer(layer, name, true, pos);
		this._update();
		return this;
	},
	
	// Function override to order layers by pos
	_addLayer: function (layer, name, overlay, pos) {
		var id = L.stamp(layer);

		this._layers[pos] = {
			layer: layer,
			name: name,
			overlay: overlay,
			id: id
		};

		if (this.options.autoZIndex && layer.setZIndex) {
			this._lastZIndex++;
			layer.setZIndex(this._lastZIndex);
		}
	}
});


var DynmapTileLayer = L.TileLayer.extend({
	_namedTiles: null,
	_cachedTileUrls: null,
	_loadQueue: null,
	_loadingTiles: null,

	initialize: function() {
		this._namedTiles = {};
		this._cachedTileUrls = {};
		this._loadQueue = [];
		this._loadingTiles = [];
	},

	createTile: function(coords, done) {
		var me = this,
			tile = document.createElement('img');

  		if (this.options.crossOrigin || this.options.crossOrigin === '') {
  			tile.crossOrigin = this.options.crossOrigin === true ? '' : this.options.crossOrigin;
  		}

  		tile.alt = '';
  		tile.setAttribute('role', 'presentation');

  		//Dynmap - Tile names
  		tile.tileName = this.getTileName(coords);
  		this._namedTiles[tile.tileName] = tile;

		tile._mapOnLoad = tile.onload = function() {
			me._tileOnLoad(done, tile);

			me._finishTileLoad(tile);
		};

		tile._mapOnError = tile.onerror = function(error) {
			me._tileOnError(done, tile, error);

			me._finishTileLoad(tile);
		};

		//Dynmap - Queue for loading
		tile.url = this.getTileUrl(coords);
		this._queueTile(tile);

		return tile;
	},

	_abortLoading: function() {
		var tile;

		for (var i in this._tiles) {
			if (!Object.prototype.hasOwnProperty.call(this._tiles, i)) {
				continue;
			}

			tile = this._tiles[i];

			//Dynmap - remove namedTiles entry
			if (tile.coords.z !== this._tileZoom) {
				// Leaflet deletes incomplete old tiles and disables their handlers.
				// Do not let later updates requeue these abandoned images.
				if (!tile.loaded && tile.el && this._namedTiles[tile.el.tileName] === tile.el) {
					delete this._namedTiles[tile.el.tileName];
				}

				if(this._loadQueue.indexOf(tile.el) > -1) {
					this._loadQueue.splice(this._loadQueue.indexOf(tile.el), 1);
				}

				if(this._loadingTiles.indexOf(tile.el) > -1) {
					this._loadingTiles.splice(this._loadingTiles.indexOf(tile.el), 1);
				}
			}
		}

		L.TileLayer.prototype._abortLoading.call(this);
		this._tickLoadQueue();
	},

	_removeTile: function(key) {
		var tile = this._tiles[key];

		if (!tile) {
			return;
		}

		//Dynmap - remove namedTiles entry
		var tileName = tile.el.tileName;

		if (tileName && this._namedTiles[tileName] === tile.el) {
			delete this._namedTiles[tileName];
		}

		//Dynmap - remove from load queue
		if(this._loadingTiles.indexOf(tile.el) > -1) {
			this._loadingTiles.splice(this._loadingTiles.indexOf(tile.el), 1);
		}

		if(this._loadQueue.indexOf(tile.el) > -1) {
			this._loadQueue.splice(this._loadQueue.indexOf(tile.el), 1);
		}

		tile.el.onerror = null;
		tile.el.onload = null;

		L.TileLayer.prototype._removeTile.call(this, key);
	},

	getTileUrl: function(coords, timestamp) {
		return this.getTileUrlFromName(this.getTileName(coords), timestamp);
	},

	getTileUrlFromName(tileName, timestamp) {
		var url = this._cachedTileUrls[tileName];

		if (!url) {
			this._cachedTileUrls[tileName] = url = this.options.mccmap.getTileUrl(tileName);
		}
		if (typeof timestamp === 'undefined') {
		   timestamp = this.options.mccmap.inittime
		}
		if(typeof timestamp !== 'undefined') {
			url += (url.indexOf('?') === -1 ? '?timestamp=' + timestamp : '&timestamp=' + timestamp);
		}

		return url;
	},

	getViewZoomRange: function() {
		return {
			min: this.options.minViewZoom === undefined ? this.options.minZoom : this.options.minViewZoom,
			max: this.options.maxViewZoom === undefined ? this.options.maxZoom : this.options.maxViewZoom
		};
	},

	getProjection: function() {
		return this.projection;
	},

	_tickLoadQueue: function() {
		while (this._loadingTiles.length < 5 && this._loadQueue.length) {
			var next = this._loadQueue.shift();
			if (this._namedTiles[next.tileName] !== next || this._loadingTiles.indexOf(next) !== -1) continue;
			this._loadingTiles.push(next);
			// Leaflet disables callbacks on old zoom levels. Restore them when
			// refreshing a retained, completed tile.
			next.onload = next._mapOnLoad;
			next.onerror = next._mapOnError;
			next.loadingUrl = next.url;
			next.src = next.url;
		}
	},

	_queueTile: function(tile) {
		if (this._namedTiles[tile.tileName] !== tile || this._loadingTiles.indexOf(tile) !== -1) return;
		if (this._loadQueue.indexOf(tile) === -1) this._loadQueue.push(tile);
		this._tickLoadQueue();
	},

	_finishTileLoad: function(tile) {
		var index = this._loadingTiles.indexOf(tile);
		if (index === -1) return; // Late events must not remove another tile.
		this._loadingTiles.splice(index, 1);
		if (tile.url !== tile.loadingUrl) this._queueTile(tile);
		this._tickLoadQueue();
	},

	getTileName: function(coords) {
		throw "getTileName not implemented";
	},

	updateNamedTile: function(name, timestamp) {
		var tile = this._namedTiles[name];

		if (tile) {
			tile.url = this.getTileUrlFromName(name, timestamp);
			this._queueTile(tile);
		}
	},

	// Some helper functions.
	zoomprefix: function(amount) {
		return ' zzzzzzzzzzzzzzzzzzzzzzzzzz'.substr(0, amount);
	},

	getTileInfo: function(coords) {
		// Stored tile levels depend on the integer native grid, not display zoom limits.
		var zoomoutlevel = Math.max(0, this.options.mapzoomout - coords.z),
			scale = 1 << zoomoutlevel,
			x = scale * coords.x,
			y = scale * coords.y;

		return {
			prefix: this.options.prefix,
			nightday: (this.options.nightandday && this.options.mccmap.serverday) ? '_day' : '',
			scaledx: x >> 5,
			scaledy: y >> 5,
			zoom: this.zoomprefix(zoomoutlevel),
			zoomprefix: (zoomoutlevel==0)?"":(this.zoomprefix(zoomoutlevel)+"_"),
			x: x,
			y: y,
			fmt: this.options['image-format'] || 'png'
		};
	}
});

function loadjs(url, completed) {
	var script = document.createElement('script');
	script.setAttribute('src', url);
	script.setAttribute('type', 'text/javascript');
	var isloaded = false;
	script.onload = function() {
		if (isloaded) { return; }
		isloaded = true;
		completed();
	};

	// Hack for IE, don't know whether this still applies to IE9.
	script.onreadystatechange = function() {
		if (script.readyState == 'loaded' || script.readyState == 'complete')
			script.onload();
	};
	(document.head || document.getElementsByTagName('head')[0]).appendChild(script);
}

function loadcss(url, completed) {
	var link = document.createElement('link');
	link.setAttribute('href', url);
	link.setAttribute('rel', 'stylesheet');
	var isloaded = false;
	if (completed) {
		link.onload = function() {
			if (isloaded) { return; }
			isloaded = true;
			completed();
		};

		// Hack for IE, don't know whether this still applies to IE9.
		link.onreadystatechange = function() {
			link.onload();
		};
	}

	(document.head || document.getElementsByTagName('head')[0]).appendChild(link);
}

function splitArgs(s) {
	var r = s.split(' ');
	delete arguments[0];
	var obj = {};
	var index = 0;
	$.each(arguments, function(argumentIndex, argument) {
		if (!argumentIndex) { return; }
		var value = r[argumentIndex-1];
		obj[argument] = value;
	});
	return obj;
}

function swtch(value, options, defaultOption) {
	return (options[value] || defaultOption || function(){})(value);
}
(function( $ ){
	$.fn.scrollHeight = function(height) {
		return this[0].scrollHeight;
	};
})($);

function Location(world, x, y, z) {
	this.world = world;
	this.x = x;
	this.y = y;
	this.z = z;
}

function namedReplace(str, obj)
{
	var startIndex = 0;
	var result = '';
	while(true) {
		var variableBegin = str.indexOf('{', startIndex);
		var variableEnd = str.indexOf('}', variableBegin+1);
		if (variableBegin < 0 || variableEnd < 0) {
			result += str.substr(startIndex);
			break;
		}
		if (variableBegin < variableEnd) {
			var variableName = str.substring(variableBegin+1, variableEnd);
			result += str.substring(startIndex, variableBegin);
			result += obj[variableName];
		} else /* found '{}' */ {
			result += str.substring(startIndex, variableBegin-1);
			result += '';
		}
		startIndex = variableEnd+1;
	}
	return result;
}

function concatURL(base, addition) {
	if(base.indexOf('?') >= 0)
		return base + escape(addition);
	
	return base + addition;
}
