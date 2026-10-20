#!/bin/zsh

set -euo pipefail
umask 077

# This creates a NEW release key. Every published APK is signed with the existing key (certificate
# SHA-256 C1:41:60:30:…:D0:D2), and an APK signed with any other key cannot update installed copies.
# To restore after a reinstall, copy expressive-launcher-release.jks and release-credentials.properties
# from the signing backup and point keystore.properties' storeFile at the copy instead.
if [[ "${1:-}" != "--create-new-key" ]]; then
    print -u2 "Refusing to create a new release key without --create-new-key (see the comment above)."
    exit 1
fi

script_dir=${0:A:h}
repository_dir=${script_dir:h}
signing_dir="${EXPRESSIVE_SIGNING_DIR:-${repository_dir:h:h}/Expressive Launcher Signing}"
keystore_path="$signing_dir/expressive-launcher-release.jks"
backup_properties_path="$signing_dir/release-credentials.properties"
repository_properties_path="$repository_dir/keystore.properties"
keytool_path=$(which keytool || true)
if [[ -z "$keytool_path" && -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" ]]; then
    keytool_path="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
fi
key_alias="expressive-launcher-release"

for target_path in "$keystore_path" "$backup_properties_path" "$repository_properties_path"; do
    if [[ -e "$target_path" ]]; then
        print -u2 "Refusing to overwrite existing signing material: $target_path"
        exit 1
    fi
done

if [[ ! -x "$keytool_path" ]]; then
    print -u2 "Android Studio keytool was not found at: $keytool_path"
    exit 1
fi

mkdir -p "$signing_dir"
signing_password=$(openssl rand -hex 32)

"$keytool_path" -genkeypair \
    -keystore "$keystore_path" \
    -storepass "$signing_password" \
    -alias "$key_alias" \
    -keypass "$signing_password" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 10000 \
    -dname "CN=Expressive Launcher Release,O=Expressive Launcher,C=US"

{
    print -r -- "storeFile=$keystore_path"
    print -r -- "storePassword=$signing_password"
    print -r -- "keyAlias=$key_alias"
    print -r -- "keyPassword=$signing_password"
} > "$backup_properties_path"

cp "$backup_properties_path" "$repository_properties_path"
chmod 600 "$keystore_path" "$backup_properties_path" "$repository_properties_path"

print "Created the Expressive Launcher release key without exposing its password."
print "Keystore: $keystore_path"
print "Offline credential backup: $backup_properties_path"
print "Ignored build configuration: $repository_properties_path"
"$keytool_path" -list -v \
    -keystore "$keystore_path" \
    -storepass "$signing_password" \
    -alias "$key_alias" \
    | sed -n '/SHA256:/p;/Valid from:/p'
