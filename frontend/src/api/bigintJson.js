import JSONbig from 'json-bigint'

const bigintJson = JSONbig({ storeAsString: true })

export function parseBigintJson(value) {
  try {
    return bigintJson.parse(value)
  } catch {
    return value
  }
}

export default parseBigintJson
