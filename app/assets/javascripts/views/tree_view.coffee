observable = require('models/observable').observable
ColorTable = require('views/color_table').ColorTable

DEFAULT_OPTIONS = {
  node_hunits: 1,
  node_vunits: 1,
  node_hpadding: 0.5,
  node_vpadding: 0.7,
  color: {
    background: '#ffffff',
    node: '#ccccdd',
    node_unloaded: '#ddddff',
    node_selected: '#bbbbbb',
    line: '#888888',
    line_selected: '#000000',
    line_loaded: '#333333',
    line_unloaded: '#999999',
  },
  connector_line_width: 1, # px
  node_line_width: 2, # px
  node_line_width_selected: 4, # px
  node_line_width_unloaded: 1, # px
  animation_speed: 0, # no animations
  mousewheel_zoom_factor: 1.2,
}

class DrawOperation
  constructor: (@canvas, tag, @depth, @zoom, @pan, @options) ->
    if tag?
      @tag = {
        id: tag.id,
        color: new ColorTable().get(tag.name)
      }

    $canvas = $(@canvas)
    @width = +Math.ceil($canvas.parent().width())
    @height = +Math.ceil($canvas.parent().height())

    @canvas.width = @width
    @canvas.height = @height

    @ctx = @canvas.getContext('2d')
    @ctx.lineStyle = @options.color.line

  clear: () ->
    @ctx.fillStyle = @options.color.background
    @ctx.fillRect(0, 0, @width, @height)

  draw: (node) ->
    @drawable_node = this._node_to_drawable_node(node)

    @px_per_hunit = @width / @drawable_node.width_with_padding / @zoom
    @px_per_vunit = @height / ((@drawable_node.height + 1) * @options.node_vpadding + @drawable_node.height)
    @px_pan = @width * ((0.5 + @pan) / @zoom - 0.5)

    this._draw_drawable_node(@drawable_node, @drawable_node.width_with_padding * 0.5, 0)

  _pixel_is_within_node: (x, y, drawable_node) ->
    px = drawable_node.px
    x >= px.left && x <= px.left + px.width && y >= px.top && y <= px.top + px.height

  _find_drawable_node_child_containing_x_within_padding: (drawable_node, x) ->
    _(drawable_node.children || []).find (n) ->
      x >= n.px.left_with_padding && x <= n.px.left_with_padding + n.px.width_with_padding

  pixel_to_nodeid: (x, y) ->
    cur = @drawable_node

    while cur? && !this._pixel_is_within_node(x, y, cur)
      cur = this._find_drawable_node_child_containing_x_within_padding(cur, x)

    cur?.node?.id

  _node_is_complete: (node) ->
    !_(node.children).any((n) => !n.loaded)

  _node_to_drawable_node: (node) ->
    drawable_node = {
      node: node,
      loaded: node.loaded,
    }

    hpadding = @options.node_hpadding

    if !node.children.length || !this._node_is_complete(node)
      drawable_node.width = node.num_documents.current
      drawable_node.width_with_padding = drawable_node.width + 2 * hpadding
      drawable_node.height = 1
    else
      drawable_node.children = _(node.children).map(this._node_to_drawable_node.bind(this))
      drawable_node.width = _(drawable_node.children).reduce(((s, n) -> s + n.width), 0)
      drawable_node.width_with_padding = _(drawable_node.children).reduce(((s, n) -> s + n.width_with_padding), 0) + (drawable_node.children.length + 1) * hpadding
      drawable_node.height = _(n.height for n in drawable_node.children).max() + 1

      x = -0.5 * drawable_node.width_with_padding + hpadding
      for child in drawable_node.children
        child.relative_x = x + child.width_with_padding * 0.5
        x += child.width_with_padding + hpadding

    drawable_node

  _node_to_line_width: (node) ->
    if node.selected
      @options.node_line_width_selected
    else if node.loaded
      @options.node_line_width
    else
      @options.node_line_width_unloaded

  _node_to_line_color: (node) ->
    if node.selected
      @options.color.line_selected
    else if node.loaded
      @options.color.line_loaded
    else
      @options.color.line_unloaded

  _node_to_connector_line_width: (node) ->
    @options.connector_line_width * node.loaded_animation_fraction.current

  _draw_tagcount: (left, top, width, height, color, fraction) ->
    return if fraction == 0

    slant_offset = height / 2
    tagwidth = 1.0 * (width + slant_offset) * fraction

    ctx = @ctx

    ctx.save()

    ctx.beginPath()
    ctx.rect(left, top, width, height)
    ctx.clip()

    ctx.fillStyle = @tag.color

    ctx.beginPath()
    ctx.moveTo(left, top)
    ctx.lineTo(left + tagwidth + slant_offset, top)
    ctx.lineTo(left + tagwidth - slant_offset, top + height)
    ctx.lineTo(left, top + height)
    ctx.fill()

    ctx.restore()

  _measure_drawable_node: (drawable_node, middle_x, level) ->
    left_units = middle_x - drawable_node.width * 0.5
    vpadding = @options.node_vpadding

    px = drawable_node.px = {
      left: left_units * @px_per_hunit - @px_pan,
      top: (level * (1 + vpadding) + vpadding) * @px_per_vunit,
      width: drawable_node.width * @px_per_hunit,
      height: @px_per_vunit,
      left_with_padding: (middle_x - drawable_node.width_with_padding * 0.5) * @px_per_hunit - @px_pan,
      width_with_padding: drawable_node.width_with_padding * @px_per_hunit,
    }
    px.middle = px.left + px.width * 0.5

  _draw_measured_drawable_node: (drawable_node) ->
    px = drawable_node.px
    node = drawable_node.node

    if @tag? && tagcount = node.tagcounts?[@tag.id]
      this._draw_tagcount(px.left, px.top, px.width, px.height, @tag.color, tagcount / node.num_documents.current)

    ctx = @ctx
    ctx.lineWidth = this._node_to_line_width(node)
    ctx.strokeStyle = this._node_to_line_color(node)
    ctx.strokeRect(px.left, px.top, px.width, px.height)

  _draw_line_from_parent_to_child: (parent_drawable_node, child_drawable_node) ->
    px1 = parent_drawable_node.px
    px2 = child_drawable_node.px

    x1 = px1.middle
    y1 = px1.top + px1.height
    x2 = px2.middle
    y2 = px2.top
    mid_y = 0.5 * (y1 + y2)

    ctx = @ctx
    ctx.lineWidth = this._node_to_connector_line_width(parent_drawable_node.node)
    ctx.beginPath()
    ctx.moveTo(x1, y1)
    ctx.bezierCurveTo(x1, mid_y + (0.1 * px1.height), x2, mid_y - (0.1 * px1.height), x2, y2)
    ctx.stroke()

  _draw_drawable_node: (drawable_node, middle_x, level) ->
    this._measure_drawable_node(drawable_node, middle_x, level)
    this._draw_measured_drawable_node(drawable_node)

    if drawable_node.children?
      for child_drawable_node in drawable_node.children
        this._draw_drawable_node(child_drawable_node, middle_x + child_drawable_node.relative_x, level + 1)
        this._draw_line_from_parent_to_child(drawable_node, child_drawable_node)

    undefined

$ = jQuery
_ = window._

class TreeView
  observable(this)

  constructor: (@div, @tree, @focus, options={}) ->
    options_color = _.extend({}, options.color, DEFAULT_OPTIONS.color)
    @options = _.extend({}, DEFAULT_OPTIONS, options, { color: options_color })

    $div = $(@div)
    @canvas = $("<canvas width=\"#{$div.width()}\" height=\"#{$div.height()}\"></canvas>")[0]

    @_nodes = {}
    @_zoom_document = { current: -1 }
    @_zoom_factor = { current: 1 }

    $div.append(@canvas)

    this._attach()
    this.update()

  _attach: () ->
    @tree.id_tree.observe 'edit', =>
      if @_zoom_document.current == -1
        root_id = @tree.id_tree.root
        if root_id?
          @_zoom_document.current = @tree.root.num_documents.current / 2

    update = this._set_needs_update.bind(this)
    @tree.observe('needs-update', update)
    @focus.observe('needs-update', update)
    @focus.observe('zoom', update)
    @focus.observe('pan', update)
    $(window).on('resize.tree-view', update)

    $(@canvas).on 'click', (e) =>
      offset = $(@canvas).offset()
      $canvas = $(@canvas)
      x = e.pageX - offset.left
      y = e.pageY - offset.top
      nodeid = this._pixel_to_nodeid(x, y)
      this._notify('click', nodeid)

    this._handle_drag()
    this._handle_mousewheel()

  _handle_drag: () ->
    $(@canvas).on 'mousedown', (e) =>
      return if e.which != 1
      e.preventDefault()

      start_x = e.pageX
      zoom = @focus.zoom
      start_pan = @focus.pan
      width = $(@canvas).width()

      update_from_event = (e) =>
        dx = e.pageX - start_x
        d_pan = (dx / width) * zoom

        this._notify('zoom-pan', { zoom: zoom, pan: start_pan - d_pan })

      $('body').on 'mousemove.tree-view', (e) ->
        update_from_event(e)
        e.preventDefault()

      $('body').on 'mouseup.tree-view', (e) ->
        update_from_event(e)
        $('body').off('.tree-view')
        e.preventDefault()

  _handle_mousewheel: () ->
    # When the user moves mouse wheel in, we divide zoom by a factor of
    # mousewheel_zoom_factor. We adjust pan to whatever will keep the mouse
    # cursor pointing to the same location, in absolute terms.
    #
    # Before zoom, absolute location is pan1 + (cursor_fraction - 0.5) * zoom1
    # After, it's pan2 + (cursor_fraction - 0.5) * zoom2
    #
    # So pan2 = pan1 + (cursor_fraction - 0.5) * zoom1 - (cursor_fraction - 0.5) * zoom2
    $(@canvas).on 'mousewheel', (e) =>
      e.preventDefault()
      offset = $(@canvas).offset()
      x = e.pageX - offset.left
      width = $(@canvas).width()

      sign = e.deltaY > 0 && 1 || -1

      zoom1 = @focus.zoom
      zoom2 = zoom1 * Math.pow(@options.mousewheel_zoom_factor, -sign)
      pan1 = @focus.pan
      relative_cursor_fraction = ((x / width) - 0.5)

      pan2 = pan1 + relative_cursor_fraction * zoom1 - relative_cursor_fraction * zoom2

      this._notify('zoom-pan', { zoom: zoom2, pan: pan2 })

  _pixel_to_nodeid: (x, y) ->
    return undefined if @tree.root is undefined

    @last_draw.pixel_to_nodeid(x, y)

  _nodeid_to_n_documents: (nodeid) ->
    exact = @tree.nodes[nodeid]?.doclist?.n
    return exact if exact?

    # Divide the number of documents that must be in unresolved siblings by
    # the number of unresolved siblings.
    parent_nodeid = @tree.id_tree.parent[nodeid]
    parent_node = @tree.nodes[parent_nodeid]

    sibling_nodeids = @tree.id_tree.children[parent_nodeid]
    n_unknown_documents = parent_node.doclist.n
    n_unloaded_siblings = 0
    for sibling_nodeid in sibling_nodeids
      sibling = @tree.nodes[sibling_nodeid]
      if sibling?
        n_unknown_documents -= sibling.doclist.n
      else
        n_unloaded_siblings += 1

    n_unknown_documents / n_unloaded_siblings # we know n_unloaded_siblings > 1 because we're here

  _redraw: () ->
    @last_draw = new DrawOperation(@canvas, @tree.state.focused_tag, @tree.animated_height.current, @focus.zoom, @focus.pan, @options)
    @last_draw.clear()

    return if @tree.root is undefined

    @last_draw.draw(@tree.root)

  update: () ->
    @tree.update()
    @focus.update()
    this._redraw()
    @_needs_update = @tree.needs_update() || @focus.needs_update()

  needs_update: () ->
    @_needs_update

  _set_needs_update: () ->
    if !@_needs_update
      @_needs_update = true
      this._notify('needs-update')

exports = require.make_export_object('views/tree_view')
exports.TreeView = TreeView
