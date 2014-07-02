define [
  'underscore'
  'backbone'
  '../models/ShowAppFacade'
  '../models/VizAppClient'
], (_, Backbone, ShowAppFacade, VizAppClient) ->
  class VizAppController
    _.extend(@::, Backbone.Events)

    constructor: (options) ->
      throw 'Must pass options.el, an HTMLElement' if !options.el
      throw 'Must pass options.state, a State' if !options.state
      throw 'Must pass options.documentSet, a DocumentSet' if !options.documentSet
      throw 'Must pass options.transactionQueue, a TransactionQueue' if !options.transactionQueue
      throw 'Must pass options.vizAppConstructors, an Object mapping viz type to an App' if !options.vizAppConstructors

      @el = options.el
      @state = options.state
      @documentSet = options.documentSet
      @transactionQueue = options.transactionQueue
      @vizAppConstructors = options.vizAppConstructors

      @facade = new ShowAppFacade
        state: @state
        tags: @documentSet.tags
        searchResults: @documentSet.searchResults

      @_setViz(@state.get('viz'))
      @listenTo(@state, 'change:viz', (__, viz) => @_setViz(viz))

    _setViz: (viz) ->
      if @vizAppClient?
        @vizAppClient.remove()
        @vizAppClient = null

      if viz?
        type = viz.get('type')
        vizApp = new @vizAppConstructors[type]
          app: @facade
          viz: viz
          transactionQueue: @transactionQueue
          documentListParams: @state.attributes.documentListParams
          document: @state.attributes.document
          taglikeCid: @state.attributes.taglikeCid
          el: @el
        @vizAppClient = new VizAppClient
          vizApp: vizApp
          state: @state
          documentSet: @documentSet