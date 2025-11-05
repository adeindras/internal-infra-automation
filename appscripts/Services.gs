function parseServices(data) {
  const service = 14
  // service
  let services = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][service] == '') {
      break;
    }
    let serviceObject = {};
    serviceObject.name = data[i][service]
    serviceObject.memory_req = data[i][service+1]
    serviceObject.memory_limit = data[i][service+2]
    serviceObject.cpu_req = data[i][service+3]
    serviceObject.cpu_limit = data[i][service+4]
    serviceObject.mesh_cpu_req = data[i][service+5]
    serviceObject.mesh_cpu_limit = data[i][service+6]
    serviceObject.mesh_memory_req = data[i][service+7]
    serviceObject.mesh_memory_limit = data[i][service+8]

    serviceObject.hpa_min = data[i][service+9]
    serviceObject.hpa_max = data[i][service+10]
    serviceObject.hpa_cpu = data[i][service+11]
    serviceObject.hpa_memory = data[i][service+12]
    			
    services.push(serviceObject);
  }
  return services
}

