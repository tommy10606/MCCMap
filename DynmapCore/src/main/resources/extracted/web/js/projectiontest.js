// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['projectiontest'] = function(mccmap, configuration) {
    var me = this;
    var marker = new L.CustomMarker(new L.LatLng(0,0,true), {
        elementCreator: function() {
            var div = document.createElement('div');
            var textContainer;
            $(div)
                .css({margin: '10px 10px 10px 10px'})
                .append(
                    textContainer = $('<span/>')
                    .css({'white-space': 'pre'})
                    .text('')
                );
            marker.setText = function(text) {
                $(textContainer).text(text);
            };
            return div;
        }
    });
    mccmap.map.addLayer(marker);

    mccmap.map.on('mousemove', function(event) {
        marker.setLatLng(event.latlng);
        if (marker.setText) {
            marker.setText('LatLng: (' + event.latlng.lat + ',' + event.latlng.lng + ')\n'+
                           'LayerPoint: (' + event.layerPoint.x + ',' + event.layerPoint.y + ')\n'+
                           'World: (?,?,?)'
                    );
        }
    });
};
