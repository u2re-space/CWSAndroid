const webpack = require('@nativescript/webpack');
const path = require('path');

module.exports = (env) => {
    webpack.init(env);
    // Learn how to customize:
    // https://docs.nativescript.org/webpack
    const config = webpack.resolveConfig();

    // NativeScript runs in a JS runtime that is NOT Node.js.
    // Ensure dependencies (socket.io-client / engine.io-client / debug) resolve to browser builds
    // and do not pull Node core modules like `tty`, `stream`, `http`, `ws`, etc.
    config.resolve = config.resolve || {};
    config.resolve.mainFields = ['browser', 'module', 'main'];
    config.resolve.fallback = {
        ...(config.resolve.fallback || {}),
        // Hard-disable Node core modules; we don't want webpack polyfills here.
        fs: false,
        path: false,
        url: false,
        os: false,
        util: false,
        tty: false,
        stream: false,
        http: false,
        https: false,
        zlib: false,
        net: false,
        tls: false,
        crypto: false,
        child_process: false,
    };

    // Force engine.io-client to use browser transports (avoid `ws` + `xmlhttprequest-ssl`)
    config.resolve.alias = {
        ...(config.resolve.alias || {}),
        'engine.io-client/build/cjs/transports/websocket.node.js': path.join(__dirname, 'node_modules', 'engine.io-client', 'build', 'cjs', 'transports', 'websocket.js'),
        'engine.io-client/build/cjs/transports/polling-xhr.node.js': path.join(__dirname, 'node_modules', 'engine.io-client', 'build', 'cjs', 'transports', 'polling-xhr.js'),
        // Force debug to browser build (avoid node.js core modules)
        'debug/src/node.js': path.join(__dirname, 'node_modules', 'debug', 'src', 'browser.js'),
    };

    return config;
};
