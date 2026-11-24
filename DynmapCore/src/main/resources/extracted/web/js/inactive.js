// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['inactive'] = function(mccmap, configuration) {
    var me = this;
    var inactivetimer = null;
    $(document)
        .ready(onactivity)
        .mousemove(onactivity)
        .mouseup(onactivity)
        .keypress(onactivity);
    function onactivity() {
        clearTimeout(inactivetimer);
        inactivetimer = setTimeout(oninactive, (configuration.timeout || 1800)*1000);
    }
    function oninactive() {
        if (configuration.showmessage) {
            alert(configuration.showmessage);
        }
        if (configuration.redirecturl) {
            window.location = configuration.redirecturl;
        }
    }
};
