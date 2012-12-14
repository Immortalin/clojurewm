(ns clojurewm.keys
  (:require [clojure.tools.logging :as log])
  (:use [clojurewm.win :only [gen-c-delegate this-proc-addr]]
        [clojure.clr.pinvoke :only [dllimports]])
  (:import [System.Runtime.InteropServices Marshal]
           [System.Windows.Forms Keys]
           [System.Threading ThreadPool Thread ThreadStart WaitCallback]))

(def WH_KEYBOARD_LL (int 13))
(def WM_KEYDOWN 0x100)
(def WM_KEYUP 0x101)
(def WM_SYSKEYDOWN 0x104)
(def WM_SYSKEYUP  0x105)

(def state (atom {:is-assigning false}))
(def hooks-context (atom {}))
(def hotkeys (atom {}))

(dllimports
 "User32.dll"
 (CallNextHookEx IntPtr [IntPtr Int32 UInt32 IntPtr])
 (SetWindowsHookEx IntPtr [Int32 IntPtr IntPtr UInt32])
 (UnhookWindowsHookEx Boolean [IntPtr])
 (GetKeyState Int16 [Int32])
 (GetForegroundWindow IntPtr [])
 (SetForegroundWindow Boolean [IntPtr])
 (SetActiveWindow IntPtr [IntPtr])
 (ShowWindow Boolean [IntPtr Int32]))

(def key-modifiers [Keys/LControlKey Keys/RControlKey
                    Keys/LMenu Keys/RMenu
                    Keys/LShiftKey Keys/RShiftKey
                    Keys/LWin Keys/RWin])

(defn get-key-state [key]
  (= 0x8000
     (bit-and (GetKeyState key) 0x8000)))

(defn get-modifiers []
  (vec (filter get-key-state key-modifiers)))

(defn is-modifier? [key]
  (some #(= key %) key-modifiers))


(defn try-focus [window]
  (ThreadPool/QueueUserWorkItem
   (gen-delegate |WaitCallback| [state]
                 (loop [times (range 5)]
                   (when (seq times)
                     (ShowWindow window (int 11))
                     (Thread/Sleep 50)
                     (ShowWindow window (int 9))
                     (let [res (SetForegroundWindow window)]
                       (log/info "SetForegroundWindow:" res)
                       (when-not res (recur (rest times)))))))))

(defn focus-window [hotkey]
  (let [{:keys [modifiers window]} hotkey]
    (when (= (get-modifiers) modifiers)
      (log/info "Focus window" hotkey)
      (try-focus window)
      (int 1))))

(defn handle-assign-key []
  (log/info "Assigning key..")
  (swap! state assoc :is-assigning true)
  (int 1))

(defn assign-key [key]
  (let [key-map {:key key :modifiers (get-modifiers) :window (GetForegroundWindow)}]
    (swap! state assoc :is-assigning false)
    (log/info "Got key" key-map)
    (swap! hotkeys assoc key key-map))
  (int 1))

(defn handle-key [key key-state]
  (when (and (= key-state :key-down) (not (is-modifier? key)))
    (cond
     (and (= Keys/K key)
          (= (get-modifiers) [Keys/LMenu Keys/LShiftKey])) (handle-assign-key)
     (@hotkeys key) (focus-window (@hotkeys key))
     (:is-assigning @state) (assign-key key)
     :else (int 0))))

(def keyboard-hook-proc
  (gen-c-delegate
   Int32 [Int32 UInt32 IntPtr] [n-code w-param l-param]
   (if (>= n-code 0)
     (try 
       (let [key (Marshal/ReadInt32 l-param)]
         (if-let [res (handle-key key (if (or (= w-param WM_KEYDOWN)
                                              (= w-param WM_SYSKEYDOWN))
                                          :key-down
                                          :key-up))]
           res
           (int 0)))
       (catch Exception ex
         (log/error ex)))
     (CallNextHookEx (:keyboard-hook @hooks-context) n-code w-param l-param))))

(defn register-hooks []
  (swap! hooks-context assoc :keyboard-hook (SetWindowsHookEx WH_KEYBOARD_LL
                                                              (:fp keyboard-hook-proc)
                                                              this-proc-addr
                                                              (uint 0)))
  (System.Windows.Forms.Application/Run))

(defn remove-hooks []
  (UnhookWindowsHookEx (:keyboard-hook @hooks-context))
  (.Abort (:thread @hooks-context)))

(defn init-hooks []
  (let [thread (Thread.
                (gen-delegate ThreadStart [] (register-hooks)))]
    (swap! hooks-context assoc :thread thread)
    (.Start thread)))