# Project Prompt: Cape Town Smart Transit and Personal Life Optimization Assistant

I want to design and build a beautiful, modern Android application focused initially on Cape Town, South Africa, public rail transport, but architected so that it can eventually become a broader personal transit and life optimization assistant.

The application should be built natively for Android using Kotlin, Jetpack Compose, Jetpack libraries, Google's latest Material Design system, Google Maps integration, Google Calendar integration, and Android's modern location, notification, scheduling, and health APIs where appropriate.

The application should have an extremely polished, modern, beautiful UI. It should feel like a modern Google or premium transportation application rather than an old government transit application.

The primary purpose is to make Cape Town train transportation easier to understand, plan, schedule, and use while eventually learning the user's normal routines and providing contextual, predictive assistance.

## 1. Cape Town Train Data

There is an unofficial website created by a developer that provides Cape Town train schedules.

The website is unofficial but is fairly accurate, actually very accurate, and contains useful train schedule information.

I want to turn the functionality of this unofficial train schedule service into a proper mobile application.

The application should retrieve the available train schedule information from the unofficial source through an appropriate scraping or data retrieval layer.

The scraper should not live directly inside the Android application if that would make maintenance difficult.

Prefer an architecture where:

```text
Android Application
        ↓
Transit API / Data Layer
        ↓
Unofficial Cape Town Train Schedule Source
```

The transit backend or data layer should normalize the scraped information into a consistent internal format.

The application should cache relevant information so that temporary failures of the unofficial website do not completely break the application.

The architecture should also allow additional transit data sources to be added later.

## 2. Google Maps Integration

The application should integrate Google Maps.

The map should display Cape Town and surrounding Western Cape railway infrastructure, including train stations where Google Maps provides the necessary data.

The application should be capable of displaying:

• Train stations
• User's current location
• Routes between stations
• Transit paths
• Walking sections
• Transfers
• Different transportation segments
• Route lines
• Estimated travel times
• Relevant transit information

The visual experience should be similar to the way Uber, Bolt, Google Maps, and other modern navigation applications visually display routes.

For example:

```text
Station A
    ↓
Walking segment
    ↓
Train
    ↓
Transfer
    ↓
Train
    ↓
Walking segment
    ↓
Station B
```

The route should be visually drawn over the map.

The user should be able to see where the route goes rather than receiving only a textual schedule.

Google Maps data should be treated as one source of information.

If Google Maps contains Cape Town railway and transit information, including information associated with PRASA and South African rail infrastructure, that information should be used where Google's APIs and licensing permit.

The application should not assume that Google Maps data is always authoritative.

## 3. Multiple Transit Data Sources

The application should support multiple sources of transit information.

For example:

```text
Source:
[ Official / Google Maps ]
[ Local Cape Town Transit Data ]
```

The exact names should depend on the actual APIs and sources available.

The user should be able to switch between available data sources.

The application can compare the results from different sources.

For example:

```text
Google Maps
16:20 → 17:05

Local Transit Data
16:25 → 17:12
```

The system should make the source clear to the user.

It should never silently combine incompatible schedule data.

If two sources disagree, the UI should be capable of showing that disagreement.

## 4. Selecting a Train Station

The user should open the application and immediately see a map.

The map should show railway stations.

If the user grants location permission, the user's current location should also be displayed.

The user can tap a railway station.

When the user taps a station, the application should identify that station and display information about it.

For example:

```text
Bellville Station

Next trains
Destination
Departure time
Platform
Service information
```

The user should then be able to select another station.

For example:

```text
From:
Bellville

To:
Salt River
```

The user can either:

• Tap another station on the map
• Search for a station by name
• Type the destination manually

Once both locations have been selected, the application retrieves the relevant schedules.

## 5. Route Planning

The route planning screen should show:

```text
FROM
Bellville

TO
Salt River

Departure options
16:10
16:25
16:40
17:05
```

Selecting a route should display:

• Departure time
• Arrival time
• Total duration
• Train segments
• Walking segments
• Transfers
• Station changes
• Relevant delays
• Source of schedule information

The route should simultaneously appear on the map.

The user should be able to move between:

```text
Map
Schedule
Route Details
```

without losing their selected journey.

## 6. Ticket and Fare Information

The application should display available train ticket and fare information where reliable data is available.

This should include, where applicable:

• Daily tickets
• Weekly tickets
• Monthly tickets
• Other available fare products

The UI should clearly indicate the source and date of the pricing information.

If pricing data cannot be verified automatically, the application should not invent prices.

## 7. Anonymous Local User

When the user opens the application for the first time, the application should create an anonymous local user profile.

There should be no mandatory account creation.

There should be no mandatory email address.

There should be no mandatory cloud account.

The anonymous profile should primarily exist locally on the device.

The goal is privacy.

The user's behavioral data should not automatically be uploaded to a cloud server.

The local profile can contain information such as:

```text
Anonymous user ID
Favorite stations
Favorite routes
Scheduled journeys
Reminder preferences
Historical journey statistics
Routine statistics
Local behavioral patterns
```

The application should be local first.

Do not build unnecessary centralized user tracking.

The system should avoid collecting unnecessary personal information.

### Important identity requirement

I originally considered storing an identifier derived from the device ID so that reinstalling the application would allow the application to recognize the same device.

The implementation should investigate the modern Android restrictions around persistent hardware identifiers.

Do not use restricted identifiers such as:

• IMEI
• MAC address
• hardware fingerprinting
• other prohibited persistent identifiers

if they violate modern Android or Google Play requirements.

Instead, use a privacy preserving mechanism such as:

```text
Locally generated UUID
+
Encrypted local profile
+
Optional encrypted backup/export
```

If Android provides a legitimate application scoped identifier suitable for this purpose, evaluate it.

The application should never attempt to bypass Android's privacy protections.

## 8. Local Database

Use Jetpack Room for persistent local data.

The database should store structured information such as:

```text
Stations
Routes
Schedules
Favorite stations
Favorite routes
Scheduled journeys
Reminder configurations
Journey history
Travel times
Behavioral statistics
User preferences
```

The database should be designed so that historical information can be aggregated rather than storing unnecessary raw location history forever.

For example, instead of keeping every GPS coordinate indefinitely:

```text
Campus → Station

Average travel time:
18 minutes

Median travel time:
16 minutes

95th percentile:
27 minutes

Observed journeys:
43
```

This gives the system useful behavioral information without requiring indefinite raw location storage.

## 9. Live Location

If the user grants location permission, the application should be able to determine the user's current location.

The application should not constantly poll GPS unnecessarily.

Use Android's modern location APIs and appropriate background mechanisms.

Prefer:

```text
Geofencing
Activity recognition
Fused location
Event driven updates
```

over:

```text
GPS every few seconds forever
```

The objective is to minimize battery consumption.

For example, if the user has an upcoming train journey:

```text
Train:
17:20

Reminder:
16:50
```

the application can increase its location awareness around the relevant time.

It does not need to continuously track the user all day.

## 10. Intelligent Journey Reminders

The user should be able to schedule a journey.

For example:

```text
Bellville
→
Salt River

Train:
17:20
```

The user can configure a reminder.

Possible reminder options:

```text
5 minutes before
10 minutes before
15 minutes before
30 minutes before
45 minutes before
60 minutes before
Custom
```

However, the application should eventually become smarter than simply using a fixed reminder.

For example, suppose the user schedules:

```text
Train departure:
17:20

Reminder:
16:50
```

At 16:50, the application checks the user's current location.

Suppose the user is currently one hour away from the station.

The application should calculate the estimated travel time.

If:

```text
Current location → Station
Estimated travel time: 47 minutes

Current time:
16:50

Train:
17:20
```

then the application knows the user is unlikely to make the train.

Instead of merely saying:

```text
Reminder: Train at 17:20
```

it could say:

```text
You are currently approximately 47 minutes from Bellville Station.

Your train departs in 30 minutes.

You are unlikely to make this train.
```

The system could then provide alternative journeys.

For example:

```text
17:20
Likely missed

17:45
Recommended

18:10
Alternative
```

## 11. Contextual Reminder Optimization

The user should not have to manually determine how early a reminder should occur.

The system should eventually learn:

```text
Where the user normally is
When the user normally leaves
How long their journey takes
How much walking they normally need
How long they normally spend getting ready
How much buffer they typically require
```

Then the reminder can become contextual.

For example:

```text
Your train leaves at 17:20.

Based on your current location and typical travel time,
you should leave by 16:37.

Leave now?
```

Another example:

```text
You normally need 21 minutes to reach Bellville Station
from campus.

Today's estimated travel time is 31 minutes.

You should leave approximately 10 minutes earlier than usual.
```

## 12. Google Calendar Integration

The application should integrate with Google Calendar where the user explicitly grants permission.

The user should be able to create or synchronize scheduled journeys with their calendar.

For example:

```text
Campus → Bellville Station
Train: 17:20
```

could become a calendar event.

The system should be careful about permissions and should not silently modify unrelated calendar events.

The user should be able to choose:

```text
Create calendar event
Do not create calendar event
```

The application should also be able to read relevant calendar events if the user grants permission.

Calendar information can help the system understand the user's intended schedule.

For example:

```text
09:00
Class

12:00
Lunch

14:00
Workshop

16:00
End of campus activity

17:20
Train
```

This provides useful context for transit planning.

## 13. Android Notifications and Alarms

The application should have its own notification and reminder system.

It should not assume that it can arbitrarily manipulate the user's default alarm application.

Where Android APIs permit integration with alarms, reminders, calendar events, or notifications, use the supported APIs.

The user should have explicit control over which systems are used.

For example:

```text
Transit App Notification
Google Calendar
Android Reminder
```

The application should not create duplicate reminders across multiple systems unless the user explicitly requests this.

## 14. Health and Wearable Data

The application should investigate integration with Android Health Connect and supported wearable health data.

If available and permitted, relevant information could include:

• Sleep duration
• Sleep start
• Wake time
• Activity
• Walking
• Exercise
• General movement

The objective is not medical diagnosis.

The objective is understanding routine context.

For example:

```text
User usually wakes:
06:45–07:10

User usually leaves home:
07:30–07:50

User usually arrives campus:
08:10–08:30
```

Over time this could help the application understand normal routines.

The user must explicitly grant access to health information.

Health information should remain local unless the user explicitly chooses otherwise.

## 15. Alarm and Wake Detection

The system could potentially use events such as:

```text
Alarm goes off
+
Wearable detects waking/activity
+
Location changes
```

to determine when the user's day actually begins.

For example:

```text
Alarm:
06:30

Detected movement:
06:37

Left home:
07:28
```

This could contribute to a statistical model of the user's routine.

The system should distinguish between:

```text
planned wake time
actual wake estimate
actual departure
actual arrival
```

rather than assuming that an alarm means the user actually woke up.

## 16. Personal Routine Learning

Over time, the application should learn patterns such as:

```text
Typical wake time
Typical sleep time
Typical campus arrival
Typical campus departure
Typical station arrival
Typical train selection
Typical walking time
Typical travel duration
Typical delays
Typical missed trains
```

The system should not require an LLM for this.

Use statistical models and deterministic calculations where possible.

For example:

```text
Monday:

Campus arrival:
08:12 average

Campus departure:
16:31 average

Campus → Bellville:
18 min average

Preferred train:
17:05
```

The system can continuously update these statistics.

## 17. Personal Transit Intelligence

The application should eventually be able to make predictions based on historical behavior.

For example:

```text
You normally leave campus around 16:30.

You have a 17:05 train today.

Your current location is campus.

Estimated travel time:
19 minutes.

You have approximately 16 minutes of buffer.
```

Or:

```text
You normally take the 17:05 train.

Today's traffic is approximately 12 minutes slower than normal.

The 17:20 train may be safer.
```

These recommendations should be based on transparent data and calculations rather than unexplained LLM output.

## 18. Personal Life Optimization Assistant

The long term goal is to evolve the application into a personal life optimization assistant.

This is not necessarily a custom LLM.

It is better understood as:

```text
Personal Context Engine
+
Memory
+
Prediction
+
Scheduling
+
Optimization
+
LLM Interface
```

The LLM should be the reasoning and conversational layer rather than the system responsible for every calculation.

The deterministic system should handle things such as:

```text
Distance
Travel time
Train schedules
Calendar events
Geofences
Historical averages
Notifications
Time calculations
Route selection
```

The LLM can handle:

```text
Natural language
Ambiguous requests
Explanations
Summaries
Planning
Conversational interaction
Complex contextual reasoning
```

## 19. MCP Architecture

The system should investigate using Model Context Protocol as an integration layer.

Potential MCP or tool interfaces could expose:

```text
Google Maps
Google Calendar
Transit schedules
Location
Health Connect
Weather
Calendar
Notifications
Local database
```

For example, the reasoning model could have access to tools such as:

```text
get_current_location()
get_calendar_events()
get_train_schedule()
get_route()
get_travel_time()
get_weather()
get_user_routine()
create_calendar_event()
create_transit_reminder()
```

The LLM should never directly control sensitive systems without appropriate permission and deterministic validation.

## 20. Compute Requirements

The system should be designed around the assumption that a continuously running large LLM is unnecessary.

Most operations are computationally cheap:

```text
GPS/location:
Very low

Calendar:
Very low

Schedule retrieval:
Very low

Database queries:
Very low

Statistics:
Very low

Route calculations:
Low

Prediction:
Low

LLM:
Occasional
```

The application should not continuously send prompts to an LLM.

Instead, use event driven inference.

For example:

```text
Train approaching
↓
Context changes
↓
Retrieve relevant data
↓
Calculate deterministic result
↓
LLM only if natural language reasoning is needed
```

A small local model in approximately the 3B to 8B parameter range should be investigated.

Potential models include:

```text
Qwen
Gemma
Phi
Llama
```

The exact model should be benchmarked on the target Android hardware.

The application should investigate on device inference using technologies such as:

```text
llama.cpp
MediaPipe
ONNX Runtime
TensorFlow Lite
Google ML Kit where appropriate
```

The LLM context window does not need to contain the user's entire history.

Instead:

```text
Local database
        ↓
Retrieval
        ↓
Relevant context
        ↓
LLM
```

Historical information should be summarized and retrieved dynamically.

## 21. Token Usage

The system should minimize token usage.

Most decisions should not require an LLM.

For example:

```text
Current location:
Campus

Train:
17:20

Travel time:
23 min

Current time:
16:48

Available buffer:
9 min
```

This is a normal calculation.

The LLM is useful when the user asks:

```text
"Can I stay on campus another 20 minutes?"
```

The system can provide structured data to the model:

```text
Current location: Campus
Current time: 16:48
Train: 17:20
Travel time: 23 minutes
Walking buffer: 5 minutes
Historical departure behavior: 16:30–16:45
```

The LLM then explains the result naturally.

## 22. Behavioral Prediction

The system should eventually calculate probabilities such as:

```text
Probability user catches 17:20:
82%

Probability user catches 17:05:
31%

Probability user chooses 17:45:
67%
```

These numbers should be generated from actual statistical models rather than invented by the LLM.

Possible approaches include:

```text
Time series analysis
Bayesian models
Clustering
Regression
Markov models
Gradient boosted models
Constraint optimization
```

The exact model should be selected based on real collected data.

## 23. Schedule Optimization

The application should eventually answer questions like:

```text
How long can I stay on campus?
```

It should calculate:

```text
Current location
+
Current time
+
Travel time
+
Walking time
+
Train schedule
+
Historical behavior
+
Required buffer
```

and determine the latest reasonable departure time.

For example:

```text
Current time:
16:05

Preferred train:
17:05

Current location:
Campus

Travel time:
22 min

Walking buffer:
8 min

Recommended departure:
16:35

Latest reasonable departure:
16:40
```

The user should still make the final decision.

The system provides information and recommendations rather than automatically controlling the user's schedule.

## 24. Transit Disruptions

The architecture should support future integration with:

• Train delays
• Cancellations
• Service disruptions
• Station closures
• Weather
• Traffic
• Alternative routes

If the application detects:

```text
17:05 train cancelled
```

it could automatically recalculate:

```text
17:20
17:45
18:10
```

and notify the user.

## 25. Weather Integration

Weather can eventually become part of the contextual system.

For example:

```text
Rain expected at 16:45.

Walking time normally:
8 min

Rain adjusted estimate:
13 min
```

This could influence departure recommendations.

## 26. Future Agentic Capabilities

Eventually the application could become an agentic personal assistant.

For example:

```text
You have a 17:20 train.

Your current location indicates that you may miss it.

The 17:45 train is available.

Would you like me to update your calendar?
```

Potential future actions could include:

• Updating calendar events
• Creating reminders
• Suggesting alternate trains
• Updating estimated arrival
• Notifying selected contacts
• Recalculating journeys
• Suggesting when to leave
• Suggesting when to return home
• Integrating weather
• Integrating traffic
• Integrating other transport providers

Sensitive actions should always require appropriate user permission or confirmation.

## 27. Privacy Architecture

Privacy should be a core architectural principle.

The default system should be:

```text
LOCAL FIRST
```

The application should minimize cloud data collection.

Sensitive information should preferably remain on device:

```text
Location history
Routine history
Health information
Calendar context
Transit history
Behavioral statistics
```

If cloud functionality is eventually introduced, it should be:

```text
Optional
Explicit
Encrypted
Transparent
Revocable
```

The user should be able to delete their local data.

The application should not silently create a centralized behavioral profile.

## 28. Android Architecture

Use modern Android architecture.

Preferred stack:

```text
Kotlin
Jetpack Compose
Material 3
Navigation Compose
Room
ViewModel
Coroutines
Flow
WorkManager
Fused Location Provider
Geofencing
Health Connect
Google Maps SDK
Google Calendar API
Android Notifications
```

Use a clean separation between:

```text
UI
Domain
Data
Transit
Location
Scheduling
Prediction
AI
```

The application should follow modern Android architectural patterns rather than placing everything inside Activities or Composables.

## 29. Suggested High Level Architecture

```text
                    ┌─────────────────────┐
                    │   Jetpack Compose   │
                    │      Material 3     │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │   Application Layer │
                    └──────────┬──────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
          ▼                    ▼                    ▼
   Transit Engine       Context Engine       Scheduling Engine
          │                    │                    │
          ▼                    ▼                    ▼
    Train Sources          Location             Calendar
    Google Maps            Geofence             Reminders
    Local Scraper          Activity             Notifications
          │                    │                    │
          └────────────────────┼────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │    Local Database   │
                    │       Room          │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │ Prediction Engine   │
                    │ Statistics / ML     │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │   AI / LLM Layer    │
                    │ Qwen / Gemma / Phi  │
                    └─────────────────────┘
```

## 30. Core MVP

Do not attempt to build the entire personal AI system immediately.

The first version should contain:

```text
1. Cape Town railway map

2. Railway station database

3. Station search

4. Train schedule retrieval

5. From → To journey planning

6. Route visualization

7. Google Maps integration

8. Schedule source switching

9. Train fare information

10. Favorite stations

11. Journey scheduling

12. Local notifications

13. Optional Google Calendar integration

14. Optional location permission

15. Local Room database

16. Modern Material 3 UI
```

Then build intelligence on top.

## 31. Second Stage

Add:

```text
Live location
Geofencing
Contextual reminders
Travel time estimation
Historical journey statistics
Delay awareness
Automatic alternative journey suggestions
Weather
```

## 32. Third Stage

Add:

```text
Personal routine learning
Sleep/wake context
Health Connect
Wearable information
Calendar context
Predictive departure times
Schedule optimization
```

## 33. Fourth Stage

Add:

```text
Local LLM
Conversational assistant
MCP tools
Natural language journey planning
Personalized explanations
Agentic scheduling
```

## 34. Long Term Vision

The final product should feel like a combination of:

```text
Google Maps
+
Cape Town train schedule application
+
Google Calendar
+
Transit planner
+
Personal routine tracker
+
Context aware reminder system
+
Private personal AI assistant
```

The important distinction is that this should not simply be another chatbot.

It should be an ambient, context aware personal assistant that understands:

```text
Where I am
Where I am going
When I need to be there
What transport is available
How long transportation normally takes
What my schedule looks like
What my normal routines are
Whether something has changed
What I should consider doing next
```

The system should continuously maintain a lightweight representation of the user's current context without requiring a large language model to constantly run.

The ultimate goal is:

```text
USER
  ↓
Current context
  ↓
Location + Calendar + Transit + Time + History
  ↓
Prediction / Optimization
  ↓
Actionable recommendation
  ↓
User decides
```

The application should prioritize reliability, privacy, battery efficiency, deterministic calculations, beautiful UX, and user control over unnecessary AI complexity.

The LLM is a supporting intelligence layer, not the foundation of the entire system.
