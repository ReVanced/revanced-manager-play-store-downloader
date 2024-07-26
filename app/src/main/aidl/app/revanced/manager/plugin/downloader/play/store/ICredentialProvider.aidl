// ICredentialProvider.aidl
package app.revanced.manager.plugin.downloader.play.store;

import app.revanced.manager.plugin.downloader.play.store.data.Credentials;

interface ICredentialProvider {
    @nullable Credentials retrieveCredentials();
}