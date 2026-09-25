#!/bin/zsh

set -euo pipefail
umask 077

script_dir=${0:A:h}
repository_dir=${script_dir:h}
signing_dir="${EXPRESSIVE_SIGNING_DIR:-${repository_dir:h:h}/Expressive Launcher Signing}"
keystore_path="$signing_dir/expressive-developer-release.jks"
backup_properties_path="$signing_dir/developer-credentials.properties"
repository_properties_path="$repository_dir/keystore.properties"
cert_export_path="$signing_dir/developer_certificate.pem"
repo_cert_path="$repository_dir/upload_certificate.pem"

keytool_path=$(which keytool || true)
if [[ -z "$keytool_path" && -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" ]]; then
    keytool_path="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
fi

if [[ -z "$keytool_path" || ! -x "$keytool_path" ]]; then
    print -u2 "keytool was not found in PATH or Android Studio installation."
    exit 1
fi

key_alias="expressive-developer-release"
developer_name="Daryl Denson"
developer_id="5547708187557586870"
org_name="Expressive Launcher"
country="US"
dname="CN=${developer_name},OU=${developer_id},O=${org_name},C=${country}"

for target_path in "$keystore_path" "$backup_properties_path"; do
    if [[ -e "$target_path" ]]; then
        print -u2 "Refusing to overwrite existing developer signing material: $target_path"
        exit 1
    fi
done

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
    -dname "$dname"

{
    print -r -- "storeFile=$keystore_path"
    print -r -- "storePassword=$signing_password"
    print -r -- "keyAlias=$key_alias"
    print -r -- "keyPassword=$signing_password"
} > "$backup_properties_path"

cp "$backup_properties_path" "$repository_properties_path"

# Export the public RFC PEM certificate for Google Play Console / Play App Signing registration
"$keytool_path" -export -rfc \
    -keystore "$keystore_path" \
    -storepass "$signing_password" \
    -alias "$key_alias" \
    -file "$cert_export_path"

cp "$cert_export_path" "$repo_cert_path"

chmod 600 "$keystore_path" "$backup_properties_path" "$repository_properties_path"
chmod 644 "$cert_export_path" "$repo_cert_path"

print "================================================================="
print "Expressive Launcher Google Play Developer Key successfully created!"
print "Developer: $developer_name (Play Console ID: $developer_id)"
print "DNAME: $dname"
print "Keystore: $keystore_path"
print "Offline credential backup: $backup_properties_path"
print "Active build configuration: $repository_properties_path"
print "Public upload certificate: $cert_export_path"
print "Repository upload certificate: $repo_cert_path"
print "================================================================="
"$keytool_path" -list -v \
    -keystore "$keystore_path" \
    -storepass "$signing_password" \
    -alias "$key_alias"
print "================================================================="
