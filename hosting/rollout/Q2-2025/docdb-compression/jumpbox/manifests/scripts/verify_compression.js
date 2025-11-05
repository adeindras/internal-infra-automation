results = []

db.adminCommand({ listDatabases: 1 }).databases.forEach((database) => {
  dbs = db.getSiblingDB(database.name)
  dbObj = {
    name: database.name,
    collections: []
  }

  coll = dbs.getCollectionNames().forEach((coll) => {
    collectionStats = {
      name: coll,
      compressionEnabled: dbs.getCollection(coll).stats().compression.enable
    }

    dbObj.collections.push(collectionStats)
  })

  results.push(dbObj)
})

print(EJSON.stringify(results))
