# Save Router

An Android app for Googlebook (and other Android devices) that lets you choose where files are saved: your NAS, Google Drive, or any folder.

## What it does

- **New downloads:** each file that lands in Downloads triggers an alert asking where it should go. The alert has one-tap buttons for your favorite NAS folders and a **Choose…** button with the full list.
- **Share from any app:** right-click a file in the Files app (or tap Share in any app), choose **Share**, then **Save to… (Save Router)**.
- **Pick several places at once**, rename the file, browse your NAS for a different folder, and optionally remove the file from Downloads afterward.

## Installing on your Googlebook

1. Open this repository's **Releases** page on your Googlebook (in the right-hand column on the repository's main page).
2. Under **Save Router (latest build)**, download **SaveRouter.apk**.
3. Open the downloaded file. If you're asked, allow your browser or Files app to install unknown apps, then tap **Install**.
4. Open **Save Router** from the launcher.

Every change pushed to this repository builds a new version automatically. New versions install over the old one and keep your settings.

## Setting up a WD My Cloud EX2 Ultra

- **NAS address:** tap **Find my NAS on this network**, or type its IP address. You'll find it in the My Cloud dashboard under Settings › Network.
- **Shared folder:** `Public`, or the share named after your My Cloud username.
- **Username and password:** your My Cloud user. Leave them blank for the Public share.
- If you see a message about SMB 1, open the My Cloud dashboard, go to Settings › Network › Windows Services, and set the maximum SMB protocol to **SMB 3**.

## Privacy

Everything runs on your device. NAS passwords are encrypted with a key stored in the device's secure hardware. The app sends nothing anywhere except to the places you choose.

> Keep this repository **private**. It contains the key used to sign your builds.
