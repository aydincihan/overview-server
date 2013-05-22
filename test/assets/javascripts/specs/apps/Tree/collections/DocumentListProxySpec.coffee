require [
  'apps/Tree/models/observable'
  'apps/Tree/collections/DocumentListProxy'
], (observable, DocumentListProxy) ->
  class DocumentList
    observable(this)

    constructor: (@documents, @n) ->

  class DocumentStore
    observable(this)

  makeDocument = (id) ->
    {
      id: id
      title: "title-#{id}"
      description: "description-#{id}"
      nodeids: [ id, 1000 ]
      tagids: [ id, 2000 ]
    }

  describe 'apps/Tree/collections/DocumentListProxy', ->
    documentList = undefined
    documentStore = undefined
    proxy = undefined
    model = undefined

    # Adds documents up to but not including index2
    addDummyDocuments = (index1, index2) ->
      while documentList.documents.length < index1
        documentList.documents.push(undefined)

      for i in [ index1 ... index2 ]
        documentList.documents.push(makeDocument(i))

      # n is supposed to be constant, but in tests we're a bit lax with that
      # So let's just adjust n to make sure it's always sane
      documentList.n = documentList.length if documentList.n < documentList.length
      undefined

    addDummyDocumentsAndNotify = (index1, index2) ->
      addDummyDocuments(index1, index2)
      documentList._notify()
      undefined

    describe 'with an empty DocumentList', ->
      beforeEach ->
        documentList = new DocumentList([], 0)
        documentStore = new DocumentStore()
        proxy = new DocumentListProxy(documentList, documentStore)
        model = proxy.model

      it 'should be empty', ->
        expect(model.get('n')).toEqual(0)
        expect(model.documents.length).toEqual(0)

      it 'should not observe anything after destroy', ->
        proxy.destroy()
        addDummyDocumentsAndNotify(0, 1)
        expect(model.documents.length).toEqual(0)

    describe 'with an empty DocumentList that has items pending', ->
      beforeEach ->
        documentList = new DocumentList([], 10)
        documentStore = new DocumentStore()
        proxy = new DocumentListProxy(documentList, documentStore)
        model = proxy.model

      it 'should have a dummy item with no ID', ->
        expect(model.documents.length).toEqual(1)
        expect(model.documents.first().id).toBeUndefined()

      it 'should have a dummy after the list partially completes', ->
        addDummyDocumentsAndNotify(0, 5)
        expect(model.documents.length).toEqual(6)
        expect(model.documents.last().id).toBeUndefined()

      it 'should fill in documents on notify', ->
        addDummyDocumentsAndNotify(0, 5)
        expect(model.documents.at(4).id).toEqual(4)
        expect(model.documents.at(4).get('title')).toEqual('title-4')

      it 'should fill in dummy documents if list is populated out of order', ->
        addDummyDocumentsAndNotify(1, 2)
        expect(model.documents.first().id).toBeUndefined()
        expect(model.documents.at(1).id).toEqual(1)

      it 'should not have a dummy when the list is complete', ->
        addDummyDocumentsAndNotify(0, 10)
        expect(model.documents.length).toEqual(10)
        expect(model.documents.last().id).toEqual(9)

    describe 'with a non-empty DocumentList', ->
      beforeEach ->
        documentList = new DocumentList([], 10)
        documentStore = new DocumentStore()
        proxy = new DocumentListProxy(documentList, documentStore)
        model = proxy.model
        addDummyDocumentsAndNotify(0, 5)

      it 'should change a model on DocumentStore:document-changed', ->
        documentStore._notify('document-changed', { id: 3, title: 'new title' })
        expect(model.documents.at(3).get('title')).toEqual('new title')

      it 'should not change an absent model on DocumentStore:document-changed', ->
        documentStore._notify('document-changed', { id: 7, title: 'new title' })
        expect(model.documents.length).toEqual(6)

      it 'should change a model on DocumentStore:document-changed even if changes are only deep', ->
        # This tests that DocumentListProxy keeps deep copies of Arrays and
        # Objects passed to it. Otherwise, if Backbone is using the same Arrays
        # and Objects as the original objects, it won't be able to detect when
        # they change.
        callback = jasmine.createSpy()
        model.documents.on('change', callback)

        document = documentList.documents[0]
        document.tagids.pop()
        documentStore._notify('document-changed', document)

        expect(callback).toHaveBeenCalled()