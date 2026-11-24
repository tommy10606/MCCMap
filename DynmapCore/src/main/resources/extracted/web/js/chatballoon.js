// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['chatballoon'] = function(mccmap, configuration) {
    var me = this;

    if(mccmap.getBoolParameterByName("hidechat"))
        return;

    me.chatpopups = {};
    $(mccmap).on('playerupdated', function(event, player) {
        var popup = me.chatpopups[player.playerId];
        if (popup) {
            var markerPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
            popup.layer.setLatLng(markerPosition);
        }
    });
    $(mccmap).on('worldchanged', function() {
        $.each(me.chatpopups, function(name, popup) {
            popup.close();
        });
    });
    $(mccmap).on('chat', function(event, message) {
        if (message.source != 'player') {
            return;
        }
        var player = mccmap.players[message.playerId];
        if (!player)
            return;
        if (mccmap.world !== player.location.world) {
            return;
        }
        var popupPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
        var popup = me.chatpopups[message.playerId];
        if (!popup) {
            me.chatpopups[message.playerId] = popup = {
                layer: new L.Popup({autoPan: configuration.focuschatballoons, closeButton: false}),
                content: $('<div/>').addClass('balloonmessages')[0]
            };
            popup.layer.setContent($(popup.content).html());

            popup.close = function() {
                if (popup.timeout) { window.clearTimeout(popup.timeout); }
                mccmap.map.removeLayer(popup.layer);
                delete me.chatpopups[message.playerId];
            };

            popup.layer.setLatLng(popupPosition);
            mccmap.map.addLayer(popup.layer);
        }

        // Add line to balloon.
        $('<div/>').addClass('balloonmessage').text(chat_encoder(message)).appendTo(popup.content);

        // Remove older lines when too many messages are shown.
        var children = $(popup.content).children();
        if (children.length > 5) {
            $(children[0]).remove();
        }

        popup.layer.setContent($(popup.content).html());

        if (popup.timeout) { window.clearTimeout(popup.timeout); }
        popup.timeout = window.setTimeout(function() {
            popup.close();
        }, 8000);

        if (configuration.focuschatballoons) {
            mccmap.panToLatLng(popupPosition);
        }
    });
};
