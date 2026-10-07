export function formatJsonText(raw) {
  if (typeof raw !== 'string' || raw === '') return raw

  let index = 0
  const source = raw

  function skipWhitespace() {
    while (/[ \t\r\n]/.test(source[index] ?? '')) index += 1
  }

  function readString() {
    const start = index
    if (source[index] !== '"') throw new Error('invalid string')
    index += 1
    while (index < source.length) {
      const character = source[index]
      if (character === '"') {
        index += 1
        return source.slice(start, index)
      }
      if (character === '\\') {
        index += 1
        const escape = source[index]
        if (!'"\\/bfnrtu'.includes(escape)) throw new Error('invalid escape')
        if (escape === 'u') {
          const digits = source.slice(index + 1, index + 5)
          if (!/^[0-9a-fA-F]{4}$/.test(digits)) throw new Error('invalid unicode escape')
          index += 4
        }
      } else if (character.charCodeAt(0) < 0x20) {
        throw new Error('invalid control character')
      }
      index += 1
    }
    throw new Error('unterminated string')
  }

  function readNumber() {
    const start = index
    if (source[index] === '-') index += 1
    if (source[index] === '0') {
      index += 1
    } else {
      if (!/[1-9]/.test(source[index] ?? '')) throw new Error('invalid number')
      while (/\d/.test(source[index] ?? '')) index += 1
    }
    if (source[index] === '.') {
      index += 1
      if (!/\d/.test(source[index] ?? '')) throw new Error('invalid fraction')
      while (/\d/.test(source[index] ?? '')) index += 1
    }
    if (source[index] === 'e' || source[index] === 'E') {
      index += 1
      if (source[index] === '+' || source[index] === '-') index += 1
      if (!/\d/.test(source[index] ?? '')) throw new Error('invalid exponent')
      while (/\d/.test(source[index] ?? '')) index += 1
    }
    return source.slice(start, index)
  }

  function readLiteral(literal) {
    if (source.slice(index, index + literal.length) !== literal) throw new Error('invalid literal')
    index += literal.length
    return literal
  }

  function readValue(depth) {
    skipWhitespace()
    const character = source[index]
    if (character === '{') return readObject(depth)
    if (character === '[') return readArray(depth)
    if (character === '"') return readString()
    if (character === '-' || /\d/.test(character ?? '')) return readNumber()
    if (character === 't') return readLiteral('true')
    if (character === 'f') return readLiteral('false')
    if (character === 'n') return readLiteral('null')
    throw new Error('invalid value')
  }

  function readObject(depth) {
    index += 1
    skipWhitespace()
    if (source[index] === '}') {
      index += 1
      return '{}'
    }
    const lines = []
    while (true) {
      skipWhitespace()
      const key = readString()
      skipWhitespace()
      if (source[index] !== ':') throw new Error('missing colon')
      index += 1
      const value = readValue(depth + 1)
      lines.push(`${'  '.repeat(depth + 1)}${key}: ${value}`)
      skipWhitespace()
      if (source[index] === '}') {
        index += 1
        break
      }
      if (source[index] !== ',') throw new Error('missing comma')
      index += 1
    }
    return `{\n${lines.join(',\n')}\n${'  '.repeat(depth)}}`
  }

  function readArray(depth) {
    index += 1
    skipWhitespace()
    if (source[index] === ']') {
      index += 1
      return '[]'
    }
    const lines = []
    while (true) {
      const value = readValue(depth + 1)
      lines.push(`${'  '.repeat(depth + 1)}${value}`)
      skipWhitespace()
      if (source[index] === ']') {
        index += 1
        break
      }
      if (source[index] !== ',') throw new Error('missing comma')
      index += 1
    }
    return `[\n${lines.join(',\n')}\n${'  '.repeat(depth)}]`
  }

  try {
    skipWhitespace()
    const formatted = readValue(0)
    skipWhitespace()
    if (index !== source.length) return raw
    return formatted
  } catch {
    return raw
  }
}
