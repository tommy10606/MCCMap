// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['chatbox'] = function(mccmap, configuration) {
    var me = this;

    if(mccmap.getBoolParameterByName("hidechat"))
        return;
    var chat = $('<div/>')
        .addClass('chat')
        .appendTo(mccmap.options.container);
    var messagelist = $('<div/>')
        .addClass('messagelist')
        .appendTo(chat);

    if (configuration.visiblelines) {
        messagelist.css('max-height', configuration.visiblelines + 'em');
    }
    else {
        messagelist.css('max-height', '6em');
    }

    if (configuration.scrollback) {
        messagelist.addClass('scrollback')
            .on('click', function() { $(this).hide(); } );
    }

    var addrow = function(row) {
        if (configuration.scrollback) {
            var c = messagelist.children();
            c.slice(0, Math.max(0, c.length-configuration.scrollback)).each(function(index, elem){ $(elem).remove(); });
        } else {
            setTimeout(function() { row.remove(); }, (configuration.messagettl * 1000));
        }
        messagelist.append(row);
        messagelist.show();
        messagelist.scrollTop(messagelist.scrollHeight());
    };

    $(mccmap).on('chat', function(event, message) {
        var playerName = message.name;
        var playerId = message.playerId;
        var messageRow = $('<div/>')
            .addClass('messagerow');

        var playerIconContainer = $('<span/>')
            .addClass('messageicon');

        if (message.source === 'player' && configuration.showplayerfaces &&
            playerId) {
            getMinecraftHead(playerId, 16, function(head) {
                messageRow.icon = $(head)
                    .addClass('playerMessageIcon')
                    .appendTo(playerIconContainer);
            });
        }

        if (message.source === 'player' && configuration.showworld && playerId && mccmap.players[playerId] && mccmap.players[playerId].location && mccmap.players[playerId].location.world) {
            var playerWorldContainer = $('<span/>')
             .addClass('messagetext')
             .text('['+mccmap.players[playerId].location.world.name+']')
             .appendTo(messageRow);
        }

        var playerNameContainer = '';
        if(message.name) {
            playerNameContainer = $('<span/>').addClass('messagetext').append(' '+message.name+': ');
        }

        var playerMessageContainer = $('<span/>')
            .addClass('messagetext')
            .text(chat_encoder(message));

        messageRow.append(playerIconContainer,playerNameContainer,playerMessageContainer);
        addrow(messageRow);
    });
};
