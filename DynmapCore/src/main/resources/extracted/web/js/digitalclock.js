// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['digitalclock'] = function(mccmap, configuration) {
    var element = $('<div/>')
        .addClass('digitalclock')
        .addClass('largeclock')
        .appendTo(mccmap.options.container);

    var timeout = null;

    var formatTime = function(time) {
        var formatDigits = function(n, digits) {
            var s = n.toString();
            while (s.length < digits) {
                s = '0' + s;
            }
            return s;
        }
        return formatDigits(time.hours, 2) + ':' + formatDigits(time.minutes, 2);
    };

    var setTime = function(servertime, rate) {
        if (timeout != null) {
            window.clearTimeout(timeout);
            timeout = null;
        }
        var time = null;
        if(servertime >= 0) {
            time = getMinecraftTime(servertime % 24000);
            element
                .addClass(time.day ? 'day' : 'night')
                .removeClass(time.night ? 'day' : 'night')
                .text(formatTime(time));
        }
        else {
            element
                .removeClass('day night')
                .text('');
        }
        if ((timeout == null) && (time != null) && rate > 0) {
            var sampledAt = performance.now();
            timeout = window.setTimeout(function() {
                timeout = null;
                setTime(servertime + (performance.now() - sampledAt) * 0.02 * rate, rate);
            }, 700);
        }
    };

    $(mccmap).on('worldupdated', function(event, update) {
        var rate = update.servertimeRate;
        setTime(update.servertime, Number.isFinite(rate) && rate >= 0 ? rate : 1);
    });
};
