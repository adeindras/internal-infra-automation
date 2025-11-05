db.adminCommand({ listDatabases: 1 }).databases.forEach((database) => {
  dbs = db.getSiblingDB(database.name)

  coll = dbs.getCollectionNames().forEach((coll) => {
    print("Enabling compression for: " + database.name + ", collection: " + coll)
    dbs.runCommand({
      collMod: coll, 
      storageEngine: { 
          documentDB: {compression: {enable: true} }
      }
    })

    print("compression is enabled on database: " + database.name + ", collection: " + coll)
  })
})
