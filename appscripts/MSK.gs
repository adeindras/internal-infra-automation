function mskParse(data) {
  // MSK
  const msk = 3
  let jsonMskObject = {}
  jsonMskObject.type = "msk"
  jsonMskObject.data = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][msk] == '') {
      break;
    }
    let mskObject = {};
    mskObject.name = data[i][msk]
    mskObject.class = data[i][msk+1]
    mskObject.zone = data[i][msk+2]
    mskObject.brokers = data[i][msk+3]
    mskObject.partitions = data[i][msk+4]
    jsonMskObject.data.push(mskObject);
  }
  return jsonMskObject;
}
