function ok(data, traceId, message = 'success') {
  return {
    code: 0,
    message,
    data: data ?? {},
    trace_id: traceId,
  }
}

function fail(code, message, traceId, details) {
  return {
    code,
    message,
    data: details ?? null,
    trace_id: traceId,
  }
}

module.exports = {
  ok,
  fail,
}
