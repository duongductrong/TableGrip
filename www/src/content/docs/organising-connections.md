---
title: Organise connections
description: Keep a long list of connections tidy with groups, favorites and the explorer's search.
---

The **explorer** on the left lists every connection, and under each connected one its schemas, tables, views and
columns. Once you have more than a handful of connections, groups and favorites keep it manageable.

## Favorites

Right-click a connection → **Add to Favorites**. Favorites are listed first, above every group, so the databases you
use every day are always at the top. You can also drag a connection onto **Favorites** to star it.

## Groups

Groups are folders in the explorer, for example one per project, client or environment.

- **Move a connection into a group:** drag it onto the group, or onto any connection already in it. Or right-click
  the connection → **Move to Group…**. The group is also under **Options** in the connection dialog.
- **Create a group while dragging:** two drop zones appear under the tree while you drag. **＋ New group** asks for a
  name; **No group** takes the connection out of its group.
- **Move several at once:** select connections with <kbd>⇧</kbd>-click or <kbd>⌘</kbd>-click, then drag them together.
- **Rename or merge:** right-click a group → **Rename Group…**. Renaming a group to the name of an existing group merges
  the two.
- **Dissolve a group:** right-click it → **Ungroup**. The connections stay; only the folder goes.
- **New connection inside a group:** right-click the group → **New Connection in &lt;group&gt;…**.
- **Take one out:** right-click the connection → **Remove from Group**.

## Search the explorer

The search field at the top of the explorer narrows the tree to connections, and to tables and columns already loaded,
whose name matches what you type. To search across everything, including the tables of every connected connection,
use [Go to anything](/docs/navigation#go-to-anything) (<kbd>⌘</kbd> <kbd>P</kbd>).

## Environments

Every connection can be tagged with an environment such as Development, Staging or Production. The tag colours the
connection's badge everywhere in the app, and Production also turns on confirmations. See
[Safety & environments](/docs/safety).
