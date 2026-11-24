// Modified for MCCMap: Paper-only support and a read-only website (2026).
componentconstructors['link'] = function(mccmap, configuration) {

  var dynmapLink = L.Control.extend({
        options: { position: 'bottomleft' },

    onAdd: function(map) {
        this._map = map;
        this._container = L.DomUtil.create('div', 'mccmap-link');

        this._linkButton = this._createButton(
                'Link', 'mccmap-link-button', this._follow, this);

        this._container.appendChild(this._linkButton);
        return this._container;
    },

    getContainer: function() {
        return this._container;
    },

    getPosition: function() {
        return this.options.position;
    },

    _createButton: function(title, className, fn, context) {
        var link = document.createElement('a');
        link.href = '#';
        link.title = title;
        link.className = className;
        link.onmouseover = function() {
            link.href = mccmap.getLink();
        };

        L.DomEvent.disableClickPropagation(link);
        L.DomEvent.addListener(link, 'click', L.DomEvent.preventDefault);
        L.DomEvent.addListener(link, 'click', fn, context);

        return link;
    },

    _follow: function() {
        var url = mccmap.getLink();
        window.location = url;
    }
  });

    var link = new dynmapLink();
    mccmap.map.addControl(link);
};
