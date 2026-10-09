// CloudFront Function (cloudfront-js-2.0), viewer request, on the default
// behaviour (the React app). Paths with a file extension are real files from
// the build (/assets/index-abc123.js, /favicon.svg) and pass through. Anything
// else (/, /info, /profile) is a client-side route: serve index.html and let
// React Router choose the screen. /api/* never reaches this function.
function handler(event) {
    var request = event.request;
    var lastSegment = request.uri.split('/').pop();
    if (lastSegment.indexOf('.') === -1) {
        request.uri = '/index.html';
    }
    return request;
}
