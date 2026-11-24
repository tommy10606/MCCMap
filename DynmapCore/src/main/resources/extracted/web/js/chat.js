// Modified for MCCMap: Paper-only support and a read-only website (2026).

componentconstructors['chat'] = function(mccmap, configuration) {
    var me = this;

    if(mccmap.getBoolParameterByName("hidechat"))
        return;

    // Provides 'chat'-events by monitoring the world-updates.
    $(mccmap).on('worldupdate', function(event, update) {
        swtch(update.type, {
            chat: function() {
                if (update.source !== 'player' || !update.playerId) return;
                $(mccmap).trigger('chat', [{source: update.source, name: update.playerName, text: update.message, playerId: update.playerId}]);
            }
        });
    });
};
