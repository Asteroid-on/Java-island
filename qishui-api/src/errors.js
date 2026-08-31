class HttpError extends Error {
  constructor(status, code, message, details) {
    super(message)
    this.name = this.constructor.name
    this.status = status
    this.code = code
    this.details = details
  }
}

class ValidationError extends HttpError {
  constructor(message, details) {
    super(400, 40000, message, details)
  }
}

class UpstreamError extends HttpError {
  constructor(message, details) {
    super(502, 50200, message, details)
  }
}

module.exports = {
  HttpError,
  ValidationError,
  UpstreamError,
}
