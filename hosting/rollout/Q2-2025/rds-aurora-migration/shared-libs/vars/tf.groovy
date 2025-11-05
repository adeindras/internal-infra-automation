// Cache for Terraform outputs
def tfOutputs = [:]

// Get all outputs (cached)
def getTFOutputs(String dir = '') {
    if (!tfOutputs[dir]) {  // Cache per directory
        def command = "terraform output -json"
        if (dir) {
            command = "cd ${dir} && ${command}"
        }
        
        def outputJson = sh(
            script: command,
            returnStdout: true
        ).trim()
        
        tfOutputs[dir] = readJSON(text: outputJson)
    }
    return tfOutputs[dir]
}

// Get single output value
def get(String key, String dir = '') {
    def outputs = getTFOutputs(dir)
    if (outputs[key]?.value != null) {
        return outputs[key].value
    }
    error("TF output '${key}' not found in ${dir ?: 'current dir'}")
}

// Get multiple outputs
def getMultiple(List keys, String dir = '') {
    def outputs = getTFOutputs(dir)
    def results = [:]
    keys.each { key ->
        if (outputs[key]?.value != null) {
            results[key] = outputs[key].value
        } else {
            error("TF output '${key}' not found")
        }
    }
    return results
}

// Return this script as the "tf" object
return this