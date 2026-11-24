// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['logo'] = function(mccmap, configuration) {

    var Logo = L.Control.extend({
        onAdd: function(map) {
            this._container = L.DomUtil.create('div', 'leaflet-control-attribution');
            this._map = map;
            this._update();
            return this._container;
        },

        getPosition: function() {
            if(configuration.position == 'top-left')
                return 'topleft';
            else if(configuration.position == 'top-right')
                return 'topright';
            else if(configuration.position == 'bottom-left')
                return 'bottomleft';
            else
                return 'bottomright';
        },

        getContainer: function() {
            return this._container;
        },

        _update: function() {
            if (!this._map) return;
            var c = this._container;
            if (configuration.linkurl) {
                c = $('<a/>').attr('href', configuration.linkurl).appendTo(c)[0];
            }
            if (configuration.logourl) {
                $(c).append($('<img/>').attr('src', configuration.logourl).attr('alt', configuration.text).appendTo(c)[0]);
            } else {
                $(c).text(configuration.text);
            }
        }
    });

    mccmap.map.options.attributionControl = false;
    if (mccmap.map.attributionControl) {
        mccmap.map.removeControl(mccmap.map.attributionControl);
        mccmap.map.attributionControl = null;
    }
    var l = new Logo();
    mccmap.map.addControl(l);
};
