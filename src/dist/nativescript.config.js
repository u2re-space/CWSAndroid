"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.default = {
    id: 'com.automata.daemon',
    appPath: '.',
    appResourcesPath: 'App_Resources',
    android: {
        v8Flags: '--expose_gc',
        markingMode: 'none',
    },
    webpackConfigPath: 'webpack.config.js',
};
