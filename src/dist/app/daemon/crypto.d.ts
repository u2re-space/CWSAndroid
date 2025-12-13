export declare const encryptJson: (obj: any, keyMaterial: string) => Promise<string>;
export declare const decryptJson: (payloadB64: string, keyMaterial: string) => Promise<any>;
