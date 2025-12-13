export type ContactItem = {
    id: string;
    name: string;
    phones: string[];
    emails: string[];
};
export declare const readContacts: () => Promise<ContactItem[]>;
