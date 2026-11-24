// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['playermarkers'] = function(mccmap, configuration) {
    var me = this;
    $(mccmap).on('playeradded', function(event, player) {
        // Create the player-marker.
        var markerPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
        player.marker = new L.CustomMarker(markerPosition, { elementCreator: function() {
            var div = document.createElement('div');
            var playerImage;

            var markerPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
            player.marker.setLatLng(markerPosition);

            // Only show player faces if canvas supported
            if(mccmap.canvassupport == false)
                configuration.showplayerfaces = false;

            $(div)
                .addClass('Marker')
                .addClass('playerMarker')
                .append(playerImage = $('<img/>').addClass(configuration.smallplayerfaces?'playerIconSm':(configuration.largeplayerfaces?'playerIconLg':'playerIcon'))
                        .attr({ src: 'images/player.png' }))
                .append(player.namefield = $('<span/>')
                    .addClass(configuration.smallplayerfaces?'playerNameSm':(configuration.largeplayerfaces?'playerNameLg':'playerName'))
                    .append(player.name));

            if (configuration.showplayerfaces) {
                if(configuration.smallplayerfaces) {
                    getMinecraftHead(player.playerId, 16, function(head) {
                        $(head)
                            .addClass('playerIconSm')
                        .prependTo(div);
                        playerImage.remove();
                    });
                }
                else if(configuration.largeplayerfaces) {
                    getMinecraftHead(player.playerId, 32, function(head) {
                        $(head)
                            .addClass('playerIconLg')
                        .prependTo(div);
                        playerImage.remove();
                    });
                }
                else if(configuration.showplayerbody) {
                    getMinecraftHead(player.playerId, 'body', function(head) {
                        $(head)
                            .addClass('playerIcon')
                        .prependTo(div);
                        playerImage.remove();
                    });
                }
                else {
                    getMinecraftHead(player.playerId, 32, function(head) {
                        $(head)
                            .addClass('playerIcon')
                        .prependTo(div);
                        playerImage.remove();
                    });
                }
            }
            if (configuration.showplayerhealth) {
                player.healthContainer = $('<div/>')
                    .addClass(configuration.smallplayerfaces?'healthContainerSm':(configuration.largeplayerfaces?'healthContainerLg':'healthContainer'))
                    .appendTo(div);
                if (player.health !== undefined && player.armor !== undefined) {
                    player.healthBar = $('<div/>')
                        .addClass('playerHealth')
                        .css('width', Math.ceil(player.health*2.5) + 'px');
                    player.armorBar = $('<div/>')
                        .addClass('playerArmor')
                        .css('width', Math.ceil(player.armor*2.5) + 'px');

                    $('<div/>')
                        .addClass('playerHealthBackground')
                        .append(player.healthBar)
                        .appendTo(player.healthContainer);
                    $('<div/>')
                        .addClass('playerArmorBackground')
                        .append(player.armorBar)
                        .appendTo(player.healthContainer);
                } else {
                    player.healthContainer.css('display','none');
                }
            }
            else {
                player.namefield.addClass('playerNameNoHealth');
            }

            return div;
        }});
        if(mccmap.world === player.location.world)
            mccmap.playermarkergroup.addLayer(player.marker);
    });
    $(mccmap).on('playerremoved', function(event, player) {
        // Remove the marker.
        mccmap.playermarkergroup.removeLayer(player.marker);
    });
    $(mccmap).on('playerupdated', function(event, player) {
        if(mccmap.world === player.location.world) {
            // Add if needed
            mccmap.playermarkergroup.addLayer(player.marker);
            // Update the marker.
            var markerPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
            player.marker.setLatLng(markerPosition);
            // Update health
            if (configuration.showplayerhealth && player.healthContainer) {
                if (player.health !== undefined && player.armor !== undefined) {
                    player.healthContainer.css('display','block');
                    player.healthBar.css('width', Math.ceil(player.health*2.5) + 'px');
                    player.armorBar.css('width', Math.ceil(player.armor*2.5) + 'px');
                } else {
                    player.healthContainer.css('display','none');
                }
            }
        } else {
            mccmap.playermarkergroup.removeLayer(player.marker);
        }
        if (player.namefield && (player.namefield.html() != player.name)) {
            player.namefield.html(player.name);
        }
    });
    // Remove marker on start of map change
    $(mccmap).on('mapchanging', function(event) {
        var name;
        for(name in mccmap.players) {
            var player = mccmap.players[name];
            // Turn off marker - let update turn it back on
            mccmap.playermarkergroup.removeLayer(player.marker);
        }
    });
    // Remove marker on start of map change
    $(mccmap).on('mapchanging', function(event) {
        var name;
        for(name in mccmap.players) {
            var player = mccmap.players[name];
            mccmap.playermarkergroup.removeLayer(player.marker);
        }
    });
    // Add markers back on end of map change
    $(mccmap).on('mapchanged', function(event) {
        var name;
        for(name in mccmap.players) {
            var player = mccmap.players[name];
            if(mccmap.world === player.location.world) {
                mccmap.playermarkergroup.addLayer(player.marker);
                var markerPosition = mccmap.getProjection().fromLocationToLatLng(player.location);
                player.marker.setLatLng(markerPosition);
            }
        }
    });

    mccmap.playermarkergroup = new L.LayerGroup();
    if(!configuration.hidebydefault)
        mccmap.map.addLayer(mccmap.playermarkergroup);
    mccmap.addToLayerSelector(mccmap.playermarkergroup, configuration.label || 'Players', configuration.layerprio || 0);
};
