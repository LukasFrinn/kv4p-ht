// kv4p-locate: prints the Mac's current location once, for the APRS "Use this computer's
// location" button. Output: "OK <lat> <lon> <accuracy m>" or "ERR <reason>".
// Bundled inside kv4p-ht-desktop.app so macOS asks for location permission for the app.
import CoreLocation
import Foundation

final class Locator: NSObject, CLLocationManagerDelegate {
    let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: finish("ERR denied", 2)
        default: manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        finish("OK \(loc.coordinate.latitude) \(loc.coordinate.longitude) \(loc.horizontalAccuracy)", 0)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // "Location unknown" is temporary (Wi-Fi scan still running); keep waiting.
        if (error as? CLError)?.code == .locationUnknown { return }
        if (error as? CLError)?.code == .denied { finish("ERR denied", 2) }
        finish("ERR \(error.localizedDescription)", 1)
    }

    func finish(_ line: String, _ code: Int32) {
        print(line)
        fflush(stdout)
        exit(code)
    }
}

let timeout = Double(CommandLine.arguments.dropFirst().first ?? "") ?? 30
let locator = Locator()
DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { locator.finish("ERR timeout", 4) }
RunLoop.main.run()
