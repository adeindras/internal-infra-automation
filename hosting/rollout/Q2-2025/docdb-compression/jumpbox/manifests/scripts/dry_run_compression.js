db.adminCommand({ listDatabases: 1 }).databases.forEach((database) => {
  dbs = db.getSiblingDB(database.name)

  coll = dbs.getCollectionNames().forEach((coll) => {
    print("DRY-RUN: enable compression on database: " + database.name + ", collection: " + coll)
  })
})
