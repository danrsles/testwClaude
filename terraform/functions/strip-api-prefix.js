// CloudFront Function (cloudfront-js-2.0), viewer request, on the /api/* behaviour.
// The browser calls /api/wants; the Spring API serves /wants. Same rewrite as
// the Vite dev proxy in frontend/vite.config.ts. Query strings are separate
// from request.uri, so they pass through untouched.
function handler(event) {
    var request = event.request;
    request.uri = request.uri.replace(/^\/api/, '') || '/';
    return request;
}
