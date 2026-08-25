import UIKit

/// The PIN dialogs: first-run setup, the gate to the admin menu, and changing it.
///
/// One reusable prompt covering the same five flows as `dialog_pin.xml`, built on
/// `UIAlertController` because that *is* the iOS dialog — the Android layout
/// exists only because an alert there cannot hold a text field.
///
/// The mandatory first-run prompt is the one that needs care: it has no cancel
/// button and re-presents itself if dismissed, which is how
/// `setCancelable(false)` is expressed on a platform where a sheet can always be
/// swiped away.
@MainActor
public enum PinPrompt {

    /// Asks once. `nil` means the user cancelled.
    public static func ask(
        on presenter: UIViewController,
        title: String,
        message: String,
        cancellable: Bool = true
    ) async -> String? {
        await withCheckedContinuation { continuation in
            var finished = false
            func finish(_ value: String?) {
                guard !finished else { return }
                finished = true
                continuation.resume(returning: value)
            }

            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addTextField { field in
                field.placeholder = Strings.pinPlaceholder
                field.isSecureTextEntry = true
                field.keyboardType = .numberPad
                field.textContentType = .oneTimeCode
            }
            alert.addAction(UIAlertAction(title: Strings.adminDone, style: .default) { _ in
                finish(alert.textFields?.first?.text ?? "")
            })
            if cancellable {
                alert.addAction(UIAlertAction(title: Strings.adminCancel, style: .cancel) { _ in
                    finish(nil)
                })
            }
            presenter.present(alert, animated: true)
        }
    }

    /// First run: create a PIN, twice, and do not take no for an answer.
    ///
    /// The Android dialog is simply not cancelable. Here the loop is the
    /// equivalent: every way out leads back to the prompt.
    public static func setUp(
        on presenter: UIViewController,
        manager: PinManager,
        banner: BannerPresenter
    ) async {
        while true {
            guard let first = await ask(
                on: presenter,
                title: Strings.pinSetTitle,
                message: Strings.pinSetMessage,
                cancellable: false
            ) else { continue }

            guard PinManager.isLongEnough(first) else {
                banner.show(Strings.pinTooShort)
                continue
            }
            guard let again = await ask(
                on: presenter,
                title: Strings.pinConfirmTitle,
                message: Strings.pinConfirmMessage,
                cancellable: false
            ) else { continue }

            guard first == again else {
                banner.show(Strings.pinMismatch)
                continue
            }
            manager.setPin(first)
            return
        }
    }

    /// The gate to the admin menu. False when the user gave up or got it wrong.
    public static func unlock(
        on presenter: UIViewController,
        manager: PinManager,
        banner: BannerPresenter
    ) async -> Bool {
        guard let pin = await ask(
            on: presenter,
            title: Strings.pinEnterTitle,
            message: Strings.pinEnterMessage
        ) else {
            return false
        }
        guard manager.verify(pin) else {
            banner.show(Strings.pinWrong)
            return false
        }
        return true
    }

    /// Current PIN, then the new one twice.
    public static func change(
        on presenter: UIViewController,
        manager: PinManager,
        banner: BannerPresenter
    ) async {
        guard let current = await ask(
            on: presenter,
            title: Strings.pinChangeTitle,
            message: Strings.pinCurrentMessage
        ) else { return }
        guard manager.verify(current) else {
            banner.show(Strings.pinWrong)
            return
        }
        guard let new = await ask(
            on: presenter,
            title: Strings.pinChangeTitle,
            message: Strings.pinNewMessage
        ) else { return }
        guard PinManager.isLongEnough(new) else {
            banner.show(Strings.pinTooShort)
            return
        }
        guard let again = await ask(
            on: presenter,
            title: Strings.pinConfirmTitle,
            message: Strings.pinConfirmMessage
        ) else { return }
        guard new == again else {
            banner.show(Strings.pinMismatch)
            return
        }
        manager.setPin(new)
        banner.show(Strings.pinChanged)
    }
}
